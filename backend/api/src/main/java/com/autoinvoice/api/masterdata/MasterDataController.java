package com.autoinvoice.api.masterdata;

import com.autoinvoice.api.http.VersionEtag;
import com.autoinvoice.api.idempotency.IdempotencyExecutor;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.audit.AuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class MasterDataController {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final IdempotencyExecutor idempotency;
    private final AuditService audit;

    public MasterDataController(JdbcClient jdbc, ObjectMapper objectMapper,
                                IdempotencyExecutor idempotency, AuditService audit) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @GetMapping("/companies")
    @PreAuthorize("hasAuthority('customer.read')")
    public List<CompanyResponse> companies(Authentication authentication,
                                           @RequestParam(name = "customer_id", required = false) UUID customerId,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "50") int limit) {
        return jdbc.sql("""
                        SELECT * FROM companies
                        WHERE tenant_id = :tenantId
                          AND (:customerId IS NULL OR customer_id = :customerId)
                          AND (:status IS NULL OR status = :status)
                          AND (:query IS NULL OR company_code ILIKE :likeQuery OR company_name ILIKE :likeQuery)
                        ORDER BY created_at DESC LIMIT :limit
                        """)
                .param("tenantId", user(authentication).tenantId()).param("customerId", customerId)
                .param("status", blank(status)).param("query", blank(q)).param("likeQuery", like(q))
                .param("limit", pageSize(limit)).query(this::mapCompany).list();
    }

    @GetMapping("/companies/{id}")
    @PreAuthorize("hasAuthority('customer.read')")
    public ResponseEntity<CompanyResponse> company(Authentication authentication, @PathVariable UUID id) {
        CompanyResponse response = findCompany(user(authentication).tenantId(), id);
        return ResponseEntity.ok().eTag(VersionEtag.format(response.version())).body(response);
    }

    @PostMapping("/companies")
    @PreAuthorize("hasAuthority('customer.write')")
    public ResponseEntity<CompanyResponse> createCompany(Authentication authentication,
                                                         @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                         @Valid @RequestBody CompanyCreateRequest request,
                                                         HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", "/api/v1/companies", request,
                CompanyResponse.class, () -> {
                    requireCustomer(actor.tenantId(), request.customerId());
                    UUID id = UuidV7.generate();
                    jdbc.sql("""
                                    INSERT INTO companies(
                                        id, tenant_id, customer_id, company_code, company_name, company_name_en,
                                        country_region, address, tax_number, invoice_title, default_currency,
                                        default_tax_rate, status
                                    ) VALUES (
                                        :id, :tenantId, :customerId, :code, :name, :nameEn,
                                        :country, :address, :taxNumber, :invoiceTitle, :currency, :taxRate, 'ACTIVE'
                                    )
                                    """)
                            .param("id", id).param("tenantId", actor.tenantId()).param("customerId", request.customerId())
                            .param("code", request.companyCode()).param("name", request.companyName())
                            .param("nameEn", request.companyNameEn()).param("country", request.countryRegion())
                            .param("address", request.address()).param("taxNumber", request.taxNumber())
                            .param("invoiceTitle", request.invoiceTitle()).param("currency", request.defaultCurrency())
                            .param("taxRate", request.defaultTaxRate()).update();
                    CompanyResponse created = findCompany(actor.tenantId(), id);
                    record(actor, "company.created", "company", id, null, created, request.reason(), servletRequest);
                    return ResponseEntity.status(HttpStatus.CREATED).eTag(VersionEtag.format(0)).body(created);
                });
    }

    @PatchMapping("/companies/{id}")
    @PreAuthorize("hasAuthority('customer.write')")
    @Transactional
    public ResponseEntity<CompanyResponse> updateCompany(Authentication authentication, @PathVariable UUID id,
                                                         @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                         @Valid @RequestBody CompanyUpdateRequest request,
                                                         HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        CompanyResponse before = findCompany(actor.tenantId(), id);
        long version = VersionEtag.parse(ifMatch);
        int changed = jdbc.sql("""
                        UPDATE companies SET
                            company_name = COALESCE(:name, company_name),
                            company_name_en = COALESCE(:nameEn, company_name_en),
                            country_region = COALESCE(:country, country_region),
                            address = COALESCE(:address, address),
                            tax_number = COALESCE(:taxNumber, tax_number),
                            invoice_title = COALESCE(:invoiceTitle, invoice_title),
                            default_currency = COALESCE(:currency, default_currency),
                            default_tax_rate = COALESCE(:taxRate, default_tax_rate),
                            status = COALESCE(:status, status), updated_at = now(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :id AND version = :version
                        """)
                .param("name", request.companyName()).param("nameEn", request.companyNameEn())
                .param("country", request.countryRegion()).param("address", request.address())
                .param("taxNumber", request.taxNumber()).param("invoiceTitle", request.invoiceTitle())
                .param("currency", request.defaultCurrency()).param("taxRate", request.defaultTaxRate())
                .param("status", request.status()).param("tenantId", actor.tenantId()).param("id", id)
                .param("version", version).update();
        requireChanged(changed, "Company", version);
        CompanyResponse after = findCompany(actor.tenantId(), id);
        record(actor, "company.updated", "company", id, before, after, request.reason(), servletRequest);
        return ResponseEntity.ok().eTag(VersionEtag.format(after.version())).body(after);
    }

    @GetMapping("/customers/{customerId}/contacts")
    @PreAuthorize("hasAuthority('customer.read')")
    public List<ContactResponse> contacts(Authentication authentication, @PathVariable UUID customerId) {
        UUID tenantId = user(authentication).tenantId();
        requireCustomer(tenantId, customerId);
        return jdbc.sql("SELECT * FROM customer_contacts WHERE tenant_id = :tenantId AND customer_id = :customerId ORDER BY created_at")
                .param("tenantId", tenantId).param("customerId", customerId).query(this::mapContact).list();
    }

    @PostMapping("/customers/{customerId}/contacts")
    @PreAuthorize("hasAuthority('customer.write')")
    public ResponseEntity<ContactResponse> createContact(Authentication authentication, @PathVariable UUID customerId,
                                                         @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                         @Valid @RequestBody ContactCreateRequest request,
                                                         HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        String path = "/api/v1/customers/" + customerId + "/contacts";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                ContactResponse.class, () -> {
            requireCustomer(actor.tenantId(), customerId);
            requireCompanyForCustomer(actor.tenantId(), request.companyId(), customerId);
            UUID id = UuidV7.generate();
            jdbc.sql("""
                            INSERT INTO customer_contacts(
                                id, tenant_id, customer_id, company_id, contact_name, contact_type,
                                email, phone, telegram, wecom, language, receives_invoice, receives_reminder
                            ) VALUES (
                                :id, :tenantId, :customerId, :companyId, :name, :type,
                                :email, :phone, :telegram, :wecom, :language, :invoice, :reminder
                            )
                            """)
                    .param("id", id).param("tenantId", actor.tenantId()).param("customerId", customerId)
                    .param("companyId", request.companyId()).param("name", request.contactName())
                    .param("type", request.contactType()).param("email", request.email()).param("phone", request.phone())
                    .param("telegram", request.telegram()).param("wecom", request.wecom()).param("language", request.language())
                    .param("invoice", request.receivesInvoice()).param("reminder", request.receivesReminder()).update();
            ContactResponse created = findContact(actor.tenantId(), id);
            record(actor, "contact.created", "contact", id, null, created, request.reason(), servletRequest);
            return ResponseEntity.status(HttpStatus.CREATED).eTag(VersionEtag.format(0)).body(created);
        });
    }

    @PatchMapping("/contacts/{id}")
    @PreAuthorize("hasAuthority('customer.write')")
    @Transactional
    public ResponseEntity<ContactResponse> updateContact(Authentication authentication, @PathVariable UUID id,
                                                         @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                         @Valid @RequestBody ContactUpdateRequest request,
                                                         HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        ContactResponse before = findContact(actor.tenantId(), id);
        requireCompanyForCustomer(actor.tenantId(), request.companyId(), before.customerId());
        long version = VersionEtag.parse(ifMatch);
        int changed = jdbc.sql("""
                        UPDATE customer_contacts SET
                            company_id = COALESCE(:companyId, company_id), contact_name = COALESCE(:name, contact_name),
                            contact_type = COALESCE(:type, contact_type), email = COALESCE(:email, email),
                            phone = COALESCE(:phone, phone), language = COALESCE(:language, language),
                            receives_invoice = COALESCE(:invoice, receives_invoice),
                            receives_reminder = COALESCE(:reminder, receives_reminder),
                            status = COALESCE(:status, status), updated_at = now(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :id AND version = :version
                        """)
                .param("companyId", request.companyId()).param("name", request.contactName())
                .param("type", request.contactType()).param("email", request.email()).param("phone", request.phone())
                .param("language", request.language()).param("invoice", request.receivesInvoice())
                .param("reminder", request.receivesReminder()).param("status", request.status())
                .param("tenantId", actor.tenantId()).param("id", id).param("version", version).update();
        requireChanged(changed, "Contact", version);
        ContactResponse after = findContact(actor.tenantId(), id);
        record(actor, "contact.updated", "contact", id, before, after, request.reason(), servletRequest);
        return ResponseEntity.ok().eTag(VersionEtag.format(after.version())).body(after);
    }

    @GetMapping("/products")
    @PreAuthorize("hasAuthority('customer.read')")
    public List<ProductResponse> products(Authentication authentication,
                                          @RequestParam(required = false) String status) {
        return jdbc.sql("SELECT * FROM products WHERE tenant_id = :tenantId AND (:status IS NULL OR status = :status) ORDER BY product_code")
                .param("tenantId", user(authentication).tenantId()).param("status", blank(status))
                .query(this::mapProduct).list();
    }

    @PostMapping("/products")
    @PreAuthorize("hasAuthority('customer.write')")
    public ResponseEntity<ProductResponse> createProduct(Authentication authentication,
                                                         @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                         @Valid @RequestBody ProductCreateRequest request,
                                                         HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", "/api/v1/products", request,
                ProductResponse.class, () -> {
                    UUID id = UuidV7.generate();
                    jdbc.sql("""
                                    INSERT INTO products(id, tenant_id, product_code, product_name, service_type,
                                                         default_unit, attributes_schema)
                                    VALUES (:id, :tenantId, :code, :name, :type, :unit, CAST(:schema AS jsonb))
                                    """)
                            .param("id", id).param("tenantId", actor.tenantId()).param("code", request.productCode())
                            .param("name", request.productName()).param("type", request.serviceType())
                            .param("unit", request.defaultUnit()).param("schema", jsonText(request.attributesSchema())).update();
                    ProductResponse created = findProduct(actor.tenantId(), id);
                    record(actor, "product.created", "product", id, null, created, request.reason(), servletRequest);
                    return ResponseEntity.status(HttpStatus.CREATED).eTag(VersionEtag.format(0)).body(created);
                });
    }

    @GetMapping("/services")
    @PreAuthorize("hasAuthority('customer.read')")
    public List<ServiceResponse> services(Authentication authentication,
                                          @RequestParam(name = "customer_id", required = false) UUID customerId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "50") int limit) {
        return jdbc.sql("""
                        SELECT * FROM services WHERE tenant_id = :tenantId
                          AND (:customerId IS NULL OR customer_id = :customerId)
                          AND (:status IS NULL OR status = :status)
                          AND (:query IS NULL OR service_no ILIKE :likeQuery OR service_name ILIKE :likeQuery)
                        ORDER BY created_at DESC LIMIT :limit
                        """)
                .param("tenantId", user(authentication).tenantId()).param("customerId", customerId)
                .param("status", blank(status)).param("query", blank(q)).param("likeQuery", like(q))
                .param("limit", pageSize(limit)).query(this::mapService).list();
    }

    @GetMapping("/services/{id}")
    @PreAuthorize("hasAuthority('customer.read')")
    public ResponseEntity<ServiceResponse> service(Authentication authentication, @PathVariable UUID id) {
        ServiceResponse response = findService(user(authentication).tenantId(), id);
        return ResponseEntity.ok().eTag(VersionEtag.format(response.version())).body(response);
    }

    @GetMapping("/services/{id}/resources")
    @PreAuthorize("hasAuthority('customer.read')")
    public List<ServiceResourceResponse> serviceResources(Authentication authentication, @PathVariable UUID id) {
        UUID tenantId = user(authentication).tenantId();
        findService(tenantId, id);
        return jdbc.sql("""
                        SELECT * FROM service_resources
                        WHERE tenant_id = :tenantId AND service_id = :serviceId
                        ORDER BY status, resource_type, resource_ref
                        """)
                .param("tenantId", tenantId).param("serviceId", id)
                .query(this::mapResource).list();
    }

    @PostMapping("/services")
    @PreAuthorize("hasAuthority('customer.write')")
    public ResponseEntity<ServiceResponse> createService(Authentication authentication,
                                                         @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                         @Valid @RequestBody ServiceCreateRequest request,
                                                         HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", "/api/v1/services", request,
                ServiceResponse.class, () -> {
                    requireCompanyForCustomer(actor.tenantId(), request.companyId(), request.customerId());
                    requireOptional(actor.tenantId(), "products", request.productId(), "product_id");
                    UUID id = UuidV7.generate();
                    jdbc.sql("""
                                    INSERT INTO services(
                                        id, tenant_id, service_no, customer_id, company_id, product_id,
                                        service_name, service_type, region, datacenter, line_name,
                                        activated_on, deactivated_on, status, attributes_json, notes
                                    ) VALUES (
                                        :id, :tenantId, :number, :customerId, :companyId, :productId,
                                        :name, :type, :region, :datacenter, :lineName,
                                        :activatedOn, :deactivatedOn, :status, CAST(:attributes AS jsonb), :notes
                                    )
                                    """)
                            .param("id", id).param("tenantId", actor.tenantId()).param("number", request.serviceNo())
                            .param("customerId", request.customerId()).param("companyId", request.companyId())
                            .param("productId", request.productId()).param("name", request.serviceName())
                            .param("type", request.serviceType()).param("region", request.region())
                            .param("datacenter", request.datacenter()).param("lineName", request.lineName())
                            .param("activatedOn", request.activatedOn()).param("deactivatedOn", request.deactivatedOn())
                            .param("status", request.status()).param("attributes", jsonText(request.attributes()))
                            .param("notes", request.notes()).update();
                    ServiceResponse created = findService(actor.tenantId(), id);
                    record(actor, "service.created", "service", id, null, created, request.reason(), servletRequest);
                    return ResponseEntity.status(HttpStatus.CREATED).eTag(VersionEtag.format(0)).body(created);
                });
    }

    @PatchMapping("/services/{id}")
    @PreAuthorize("hasAuthority('customer.write')")
    @Transactional
    public ResponseEntity<ServiceResponse> updateService(Authentication authentication, @PathVariable UUID id,
                                                         @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                         @Valid @RequestBody ServiceUpdateRequest request,
                                                         HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        ServiceResponse before = findService(actor.tenantId(), id);
        long version = VersionEtag.parse(ifMatch);
        int changed = jdbc.sql("""
                        UPDATE services SET service_name = COALESCE(:name, service_name),
                            region = COALESCE(:region, region), datacenter = COALESCE(:datacenter, datacenter),
                            line_name = COALESCE(:lineName, line_name), activated_on = COALESCE(:activatedOn, activated_on),
                            deactivated_on = COALESCE(:deactivatedOn, deactivated_on), status = COALESCE(:status, status),
                            attributes_json = COALESCE(CAST(:attributes AS jsonb), attributes_json),
                            notes = COALESCE(:notes, notes), updated_at = now(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :id AND version = :version
                        """)
                .param("name", request.serviceName()).param("region", request.region())
                .param("datacenter", request.datacenter()).param("lineName", request.lineName())
                .param("activatedOn", request.activatedOn()).param("deactivatedOn", request.deactivatedOn())
                .param("status", request.status()).param("attributes", nullableJsonText(request.attributes()))
                .param("notes", request.notes()).param("tenantId", actor.tenantId()).param("id", id)
                .param("version", version).update();
        requireChanged(changed, "Service", version);
        ServiceResponse after = findService(actor.tenantId(), id);
        record(actor, "service.updated", "service", id, before, after, request.reason(), servletRequest);
        return ResponseEntity.ok().eTag(VersionEtag.format(after.version())).body(after);
    }

    @PostMapping("/services/{serviceId}/resources")
    @PreAuthorize("hasAuthority('customer.write')")
    public ResponseEntity<ServiceResourceResponse> addServiceResource(Authentication authentication,
                                                                      @PathVariable UUID serviceId,
                                                                      @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                                      @Valid @RequestBody ServiceResourceCreateRequest request,
                                                                      HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        String path = "/api/v1/services/" + serviceId + "/resources";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                ServiceResourceResponse.class, () -> {
                    findService(actor.tenantId(), serviceId);
                    UUID id = UuidV7.generate();
                    jdbc.sql("""
                                    INSERT INTO service_resources(
                                        id, tenant_id, service_id, resource_type, resource_ref, display_name,
                                        attributes_json, effective_from, effective_to
                                    ) VALUES (
                                        :id, :tenantId, :serviceId, :type, :reference, :displayName,
                                        CAST(:attributes AS jsonb), :effectiveFrom, :effectiveTo
                                    )
                                    """)
                            .param("id", id).param("tenantId", actor.tenantId()).param("serviceId", serviceId)
                            .param("type", request.resourceType()).param("reference", request.resourceRef())
                            .param("displayName", request.displayName()).param("attributes", jsonText(request.attributes()))
                            .param("effectiveFrom", request.effectiveFrom()).param("effectiveTo", request.effectiveTo()).update();
                    ServiceResourceResponse created = findResource(actor.tenantId(), id);
                    record(actor, "service.resource.created", "service_resource", id, null, created,
                            request.reason(), servletRequest);
                    return ResponseEntity.status(HttpStatus.CREATED).eTag(VersionEtag.format(0)).body(created);
                });
    }

    @GetMapping("/contracts")
    @PreAuthorize("hasAnyAuthority('customer.read','contract.write')")
    public List<ContractResponse> contracts(Authentication authentication,
                                            @RequestParam(name = "customer_id", required = false) UUID customerId,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(defaultValue = "50") int limit) {
        return jdbc.sql("""
                        SELECT * FROM contracts WHERE tenant_id = :tenantId
                          AND (:customerId IS NULL OR customer_id = :customerId)
                          AND (:status IS NULL OR status = :status)
                          AND (:query IS NULL OR contract_no ILIKE :likeQuery OR contract_name ILIKE :likeQuery)
                        ORDER BY created_at DESC LIMIT :limit
                        """)
                .param("tenantId", user(authentication).tenantId()).param("customerId", customerId)
                .param("status", blank(status)).param("query", blank(q)).param("likeQuery", like(q))
                .param("limit", pageSize(limit)).query(this::mapContract).list();
    }

    @GetMapping("/contracts/{id}")
    @PreAuthorize("hasAnyAuthority('customer.read','contract.write')")
    public ResponseEntity<ContractResponse> contract(Authentication authentication, @PathVariable UUID id) {
        ContractResponse response = findContract(user(authentication).tenantId(), id);
        return ResponseEntity.ok().eTag(VersionEtag.format(response.version())).body(response);
    }

    @GetMapping("/contracts/{id}/items")
    @PreAuthorize("hasAnyAuthority('customer.read','contract.write','usage.sync')")
    public List<ContractItemResponse> contractItems(Authentication authentication, @PathVariable UUID id) {
        UUID tenantId = user(authentication).tenantId();
        findContract(tenantId, id);
        return jdbc.sql("""
                        SELECT * FROM contract_items
                        WHERE tenant_id = :tenantId AND contract_id = :contractId
                        ORDER BY sort_order, contract_item_no
                        """)
                .param("tenantId", tenantId).param("contractId", id).query(this::mapContractItem).list();
    }

    @PostMapping("/contracts")
    @PreAuthorize("hasAuthority('contract.write')")
    public ResponseEntity<ContractResponse> createContract(Authentication authentication,
                                                           @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                           @Valid @RequestBody ContractCreateRequest request,
                                                           HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", "/api/v1/contracts", request,
                ContractResponse.class, () -> {
                    requireCompanyForCustomer(actor.tenantId(), request.companyId(), request.customerId());
                    UUID id = UuidV7.generate();
                    jdbc.sql("""
                                    INSERT INTO contracts(
                                        id, tenant_id, contract_no, customer_id, company_id, contract_name,
                                        effective_from, effective_to, auto_renew, billing_cycle, billing_day,
                                        payment_terms_days, currency_code, tax_rate, tax_inclusive, notes
                                    ) VALUES (
                                        :id, :tenantId, :number, :customerId, :companyId, :name,
                                        :effectiveFrom, :effectiveTo, :autoRenew, :billingCycle, :billingDay,
                                        :paymentTerms, :currency, :taxRate, :taxInclusive, :notes
                                    )
                                    """)
                            .param("id", id).param("tenantId", actor.tenantId()).param("number", request.contractNo())
                            .param("customerId", request.customerId()).param("companyId", request.companyId())
                            .param("name", request.contractName()).param("effectiveFrom", request.effectiveFrom())
                            .param("effectiveTo", request.effectiveTo()).param("autoRenew", request.autoRenew())
                            .param("billingCycle", request.billingCycle()).param("billingDay", request.billingDay())
                            .param("paymentTerms", request.paymentTermsDays()).param("currency", request.currencyCode())
                            .param("taxRate", request.taxRate()).param("taxInclusive", request.taxInclusive())
                            .param("notes", request.notes()).update();
                    ContractResponse created = findContract(actor.tenantId(), id);
                    record(actor, "contract.created", "contract", id, null, created, request.reason(), servletRequest);
                    return ResponseEntity.status(HttpStatus.CREATED).eTag(VersionEtag.format(0)).body(created);
                });
    }

    @PatchMapping("/contracts/{id}")
    @PreAuthorize("hasAuthority('contract.write')")
    @Transactional
    public ResponseEntity<ContractResponse> updateContract(Authentication authentication, @PathVariable UUID id,
                                                           @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                           @Valid @RequestBody ContractUpdateRequest request,
                                                           HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        ContractResponse before = findContract(actor.tenantId(), id);
        if (!List.of("DRAFT", "PENDING_APPROVAL").contains(before.status())) {
            throw new DomainException("CONTRACT_IMMUTABLE", "Only draft contracts can change commercial terms", 409,
                    Map.of("status", before.status()));
        }
        long version = VersionEtag.parse(ifMatch);
        int changed = jdbc.sql("""
                        UPDATE contracts SET contract_name = COALESCE(:name, contract_name),
                            effective_from = COALESCE(:effectiveFrom, effective_from),
                            effective_to = COALESCE(:effectiveTo, effective_to),
                            auto_renew = COALESCE(:autoRenew, auto_renew), billing_day = COALESCE(:billingDay, billing_day),
                            payment_terms_days = COALESCE(:paymentTerms, payment_terms_days),
                            tax_rate = COALESCE(:taxRate, tax_rate), tax_inclusive = COALESCE(:taxInclusive, tax_inclusive),
                            notes = COALESCE(:notes, notes), updated_at = now(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :id AND version = :version
                        """)
                .param("name", request.contractName()).param("effectiveFrom", request.effectiveFrom())
                .param("effectiveTo", request.effectiveTo()).param("autoRenew", request.autoRenew())
                .param("billingDay", request.billingDay()).param("paymentTerms", request.paymentTermsDays())
                .param("taxRate", request.taxRate()).param("taxInclusive", request.taxInclusive())
                .param("notes", request.notes()).param("tenantId", actor.tenantId()).param("id", id)
                .param("version", version).update();
        requireChanged(changed, "Contract", version);
        ContractResponse after = findContract(actor.tenantId(), id);
        record(actor, "contract.updated", "contract", id, before, after, request.reason(), servletRequest);
        return ResponseEntity.ok().eTag(VersionEtag.format(after.version())).body(after);
    }

    @PostMapping("/contracts/{id}/activate")
    @PreAuthorize("hasAuthority('contract.write')")
    public ResponseEntity<ContractResponse> activateContract(Authentication authentication, @PathVariable UUID id,
                                                             @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                             @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                             @Valid @RequestBody ReasonRequest request,
                                                             HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        long version = VersionEtag.parse(ifMatch);
        String path = "/api/v1/contracts/" + id + "/activate";
        ActivateCommand command = new ActivateCommand(version, request.reason());
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, command,
                ContractResponse.class, () -> {
            ContractResponse before = findContract(actor.tenantId(), id);
            if (!List.of("DRAFT", "PENDING_APPROVAL").contains(before.status())) {
                throw new DomainException("INVALID_CONTRACT_STATUS", "Contract cannot be activated from its current status", 409,
                        Map.of("status", before.status()));
            }
            int changed = jdbc.sql("""
                            UPDATE contracts SET status = 'ACTIVE', updated_at = now(), version = version + 1
                            WHERE tenant_id = :tenantId AND id = :id AND version = :version
                              AND status IN ('DRAFT', 'PENDING_APPROVAL')
                            """)
                    .param("tenantId", actor.tenantId()).param("id", id).param("version", version).update();
            requireChanged(changed, "Contract", version);
            ContractResponse after = findContract(actor.tenantId(), id);
            record(actor, "contract.activated", "contract", id, before, after, request.reason(), servletRequest);
            return ResponseEntity.ok().eTag(VersionEtag.format(after.version())).body(after);
        });
    }

    @PostMapping("/contracts/{contractId}/items")
    @PreAuthorize("hasAuthority('contract.write')")
    public ResponseEntity<ContractItemResponse> addContractItem(Authentication authentication,
                                                               @PathVariable UUID contractId,
                                                               @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                               @Valid @RequestBody ContractItemCreateRequest request,
                                                               HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        String path = "/api/v1/contracts/" + contractId + "/items";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                ContractItemResponse.class, () -> {
                    ContractResponse contract = findContract(actor.tenantId(), contractId);
                    ServiceResponse service = findService(actor.tenantId(), request.serviceId());
                    if (!contract.customerId().equals(service.customerId()) || !contract.companyId().equals(service.companyId())) {
                        throw new DomainException("RELATIONSHIP_MISMATCH",
                                "Contract item service must belong to the contract customer and company", 422, Map.of());
                    }
                    requireOptional(actor.tenantId(), "pricing_rules", request.pricingRuleId(), "pricing_rule_id");
                    UUID id = UuidV7.generate();
                    jdbc.sql("""
                                    INSERT INTO contract_items(
                                        id, tenant_id, contract_item_no, contract_id, service_id, pricing_rule_id,
                                        item_name, billing_type, billing_cycle, effective_from, effective_to,
                                        default_quantity, unit, tax_category, auto_bill, visible_on_invoice,
                                        sort_order, status, attributes_json
                                    ) VALUES (
                                        :id, :tenantId, :number, :contractId, :serviceId, :pricingRuleId,
                                        :name, :billingType, :billingCycle, :effectiveFrom, :effectiveTo,
                                        :quantity, :unit, :taxCategory, :autoBill, :visible, :sortOrder,
                                        :status, CAST(:attributes AS jsonb)
                                    )
                                    """)
                            .param("id", id).param("tenantId", actor.tenantId()).param("number", request.contractItemNo())
                            .param("contractId", contractId).param("serviceId", request.serviceId())
                            .param("pricingRuleId", request.pricingRuleId()).param("name", request.itemName())
                            .param("billingType", request.billingType()).param("billingCycle", request.billingCycle())
                            .param("effectiveFrom", request.effectiveFrom()).param("effectiveTo", request.effectiveTo())
                            .param("quantity", request.defaultQuantity()).param("unit", request.unit())
                            .param("taxCategory", request.taxCategory()).param("autoBill", request.autoBill())
                            .param("visible", request.visibleOnInvoice()).param("sortOrder", request.sortOrder())
                            .param("status", request.status()).param("attributes", jsonText(request.attributes())).update();
                    ContractItemResponse created = findContractItem(actor.tenantId(), id);
                    record(actor, "contract.item.created", "contract_item", id, null, created,
                            request.reason(), servletRequest);
                    return ResponseEntity.status(HttpStatus.CREATED).eTag(VersionEtag.format(0)).body(created);
                });
    }

    @PatchMapping("/contract-items/{id}")
    @PreAuthorize("hasAuthority('contract.write')")
    @Transactional
    public ResponseEntity<ContractItemResponse> updateContractItem(Authentication authentication, @PathVariable UUID id,
                                                                  @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                                  @Valid @RequestBody ContractItemUpdateRequest request,
                                                                  HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        ContractItemResponse before = findContractItem(actor.tenantId(), id);
        long version = VersionEtag.parse(ifMatch);
        int changed = jdbc.sql("""
                        UPDATE contract_items SET item_name = COALESCE(:name, item_name),
                            effective_to = COALESCE(:effectiveTo, effective_to),
                            default_quantity = COALESCE(:quantity, default_quantity),
                            auto_bill = COALESCE(:autoBill, auto_bill),
                            visible_on_invoice = COALESCE(:visible, visible_on_invoice),
                            sort_order = COALESCE(:sortOrder, sort_order), status = COALESCE(:status, status),
                            attributes_json = COALESCE(CAST(:attributes AS jsonb), attributes_json),
                            updated_at = now(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :id AND version = :version
                        """)
                .param("name", request.itemName()).param("effectiveTo", request.effectiveTo())
                .param("quantity", request.defaultQuantity()).param("autoBill", request.autoBill())
                .param("visible", request.visibleOnInvoice()).param("sortOrder", request.sortOrder())
                .param("status", request.status()).param("attributes", nullableJsonText(request.attributes()))
                .param("tenantId", actor.tenantId()).param("id", id).param("version", version).update();
        requireChanged(changed, "Contract item", version);
        ContractItemResponse after = findContractItem(actor.tenantId(), id);
        record(actor, "contract.item.updated", "contract_item", id, before, after,
                request.reason(), servletRequest);
        return ResponseEntity.ok().eTag(VersionEtag.format(after.version())).body(after);
    }

    private CompanyResponse findCompany(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM companies WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::mapCompany).optional()
                .orElseThrow(() -> notFound("company_id", id));
    }

    private ContactResponse findContact(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM customer_contacts WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::mapContact).optional()
                .orElseThrow(() -> notFound("contact_id", id));
    }

    private ProductResponse findProduct(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM products WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::mapProduct).optional()
                .orElseThrow(() -> notFound("product_id", id));
    }

    private ServiceResponse findService(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM services WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::mapService).optional()
                .orElseThrow(() -> notFound("service_id", id));
    }

    private ServiceResourceResponse findResource(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM service_resources WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::mapResource).optional()
                .orElseThrow(() -> notFound("resource_id", id));
    }

    private ContractResponse findContract(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM contracts WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::mapContract).optional()
                .orElseThrow(() -> notFound("contract_id", id));
    }

    private ContractItemResponse findContractItem(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM contract_items WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::mapContractItem).optional()
                .orElseThrow(() -> notFound("contract_item_id", id));
    }

    private CompanyResponse mapCompany(ResultSet rs, int row) throws SQLException {
        return new CompanyResponse(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getString("company_code"), rs.getString("company_name"), rs.getString("company_name_en"),
                rs.getString("country_region"), rs.getString("address"), rs.getString("tax_number"),
                rs.getString("invoice_title"), rs.getString("default_currency"), rs.getBigDecimal("default_tax_rate"),
                rs.getString("status"), rs.getLong("version"));
    }

    private ContactResponse mapContact(ResultSet rs, int row) throws SQLException {
        return new ContactResponse(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getString("contact_name"), rs.getString("contact_type"),
                rs.getString("email"), rs.getString("phone"), rs.getString("language"),
                rs.getBoolean("receives_invoice"), rs.getBoolean("receives_reminder"), rs.getString("status"),
                rs.getLong("version"));
    }

    private ProductResponse mapProduct(ResultSet rs, int row) throws SQLException {
        return new ProductResponse(rs.getObject("id", UUID.class), rs.getString("product_code"),
                rs.getString("product_name"), rs.getString("service_type"), rs.getString("default_unit"),
                json(rs.getString("attributes_schema")), rs.getString("status"), rs.getLong("version"));
    }

    private ServiceResponse mapService(ResultSet rs, int row) throws SQLException {
        return new ServiceResponse(rs.getObject("id", UUID.class), rs.getString("service_no"),
                rs.getObject("customer_id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("product_id", UUID.class), rs.getString("service_name"), rs.getString("service_type"),
                rs.getString("region"), rs.getString("datacenter"), rs.getString("line_name"),
                rs.getObject("activated_on", LocalDate.class), rs.getObject("deactivated_on", LocalDate.class),
                rs.getString("status"), json(rs.getString("attributes_json")), rs.getString("notes"),
                rs.getLong("version"));
    }

    private ServiceResourceResponse mapResource(ResultSet rs, int row) throws SQLException {
        return new ServiceResourceResponse(rs.getObject("id", UUID.class), rs.getObject("service_id", UUID.class),
                rs.getString("resource_type"), rs.getString("resource_ref"), rs.getString("display_name"),
                json(rs.getString("attributes_json")), rs.getObject("effective_from", OffsetDateTime.class),
                rs.getObject("effective_to", OffsetDateTime.class), rs.getString("status"), rs.getLong("version"));
    }

    private ContractResponse mapContract(ResultSet rs, int row) throws SQLException {
        return new ContractResponse(rs.getObject("id", UUID.class), rs.getString("contract_no"),
                rs.getObject("customer_id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getString("contract_name"), rs.getObject("effective_from", LocalDate.class),
                rs.getObject("effective_to", LocalDate.class), rs.getBoolean("auto_renew"),
                rs.getString("billing_cycle"), rs.getObject("billing_day", Integer.class),
                rs.getInt("payment_terms_days"), rs.getString("currency_code"), rs.getBigDecimal("tax_rate"),
                rs.getBoolean("tax_inclusive"), rs.getString("status"), rs.getString("notes"), rs.getLong("version"));
    }

    private ContractItemResponse mapContractItem(ResultSet rs, int row) throws SQLException {
        return new ContractItemResponse(rs.getObject("id", UUID.class), rs.getString("contract_item_no"),
                rs.getObject("contract_id", UUID.class), rs.getObject("service_id", UUID.class),
                rs.getObject("pricing_rule_id", UUID.class), rs.getString("item_name"), rs.getString("billing_type"),
                rs.getString("billing_cycle"), rs.getObject("effective_from", OffsetDateTime.class),
                rs.getObject("effective_to", OffsetDateTime.class), rs.getBigDecimal("default_quantity"),
                rs.getString("unit"), rs.getString("tax_category"), rs.getBoolean("auto_bill"),
                rs.getBoolean("visible_on_invoice"), rs.getInt("sort_order"), rs.getString("status"),
                json(rs.getString("attributes_json")), rs.getLong("version"));
    }

    private void requireCustomer(UUID tenantId, UUID customerId) {
        requireOptional(tenantId, "customers", customerId, "customer_id");
    }

    private void requireCompanyForCustomer(UUID tenantId, UUID companyId, UUID customerId) {
        if (companyId == null) {
            return;
        }
        boolean exists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM companies WHERE tenant_id = :tenantId AND id = :companyId AND customer_id = :customerId)")
                .param("tenantId", tenantId).param("companyId", companyId).param("customerId", customerId)
                .query(Boolean.class).single();
        if (!exists) {
            throw new DomainException("RELATIONSHIP_MISMATCH", "Company does not belong to the selected customer", 422,
                    Map.of("company_id", companyId, "customer_id", customerId));
        }
    }

    private void requireOptional(UUID tenantId, String table, UUID id, String field) {
        if (id == null) {
            return;
        }
        if (!List.of("customers", "products", "pricing_rules").contains(table)) {
            throw new IllegalArgumentException("Unsupported lookup table");
        }
        boolean exists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM " + table + " WHERE tenant_id = :tenantId AND id = :id)")
                .param("tenantId", tenantId).param("id", id).query(Boolean.class).single();
        if (!exists) {
            throw notFound(field, id);
        }
    }

    private void requireChanged(int changed, String resource, long version) {
        if (changed != 1) {
            throw new DomainException("VERSION_CONFLICT", resource + " was modified by another request", 409,
                    Map.of("expected_version", version));
        }
    }

    private DomainException notFound(String field, UUID id) {
        return new DomainException("RESOURCE_NOT_FOUND", "Resource was not found", 404, Map.of(field, id));
    }

    private AuthenticatedUser user(Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    private void record(AuthenticatedUser actor, String action, String type, UUID id,
                        Object before, Object after, String reason, HttpServletRequest request) {
        audit.record(actor.tenantId(), "USER", actor.userId(), actor.displayName(), action, type, id,
                before, after, reason, request.getHeader("X-Request-Id"));
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String like(String value) {
        String normalized = blank(value);
        return normalized == null ? null : "%" + normalized + "%";
    }

    private int pageSize(int value) {
        return Math.max(1, Math.min(value, 200));
    }

    private String jsonText(JsonNode node) {
        return node == null || node.isNull() ? "{}" : node.toString();
    }

    private String nullableJsonText(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }

    private JsonNode json(String value) throws SQLException {
        try {
            return objectMapper.readTree(value == null ? "{}" : value);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid JSON in database", exception);
        }
    }

    public record CompanyCreateRequest(@NotNull UUID customerId,
                                       @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9-]{2,63}") String companyCode,
                                       @NotBlank String companyName, String companyNameEn, String countryRegion,
                                       String address, String taxNumber, String invoiceTitle,
                                       @NotBlank @Pattern(regexp = "[A-Z]{3}") String defaultCurrency,
                                       BigDecimal defaultTaxRate, @NotBlank String reason) {
    }

    public record CompanyUpdateRequest(String companyName, String companyNameEn, String countryRegion,
                                       String address, String taxNumber, String invoiceTitle,
                                       @Pattern(regexp = "[A-Z]{3}") String defaultCurrency,
                                       BigDecimal defaultTaxRate, String status, @NotBlank String reason) {
    }

    public record CompanyResponse(UUID id, UUID customerId, String companyCode, String companyName,
                                  String companyNameEn, String countryRegion, String address, String taxNumber,
                                  String invoiceTitle, String defaultCurrency, BigDecimal defaultTaxRate,
                                  String status, long version) {
    }

    public record ContactCreateRequest(UUID companyId, @NotBlank String contactName, @NotBlank String contactType,
                                       @Email String email, String phone, String telegram, String wecom, String language,
                                       boolean receivesInvoice, boolean receivesReminder, @NotBlank String reason) {
    }

    public record ContactUpdateRequest(UUID companyId, String contactName, String contactType, @Email String email,
                                       String phone, String language, Boolean receivesInvoice,
                                       Boolean receivesReminder, String status, @NotBlank String reason) {
    }

    public record ContactResponse(UUID id, UUID customerId, UUID companyId, String contactName, String contactType,
                                  String email, String phone, String language, boolean receivesInvoice,
                                  boolean receivesReminder, String status, long version) {
    }

    public record ProductCreateRequest(@NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{2,63}") String productCode,
                                       @NotBlank String productName, @NotBlank String serviceType, String defaultUnit,
                                       JsonNode attributesSchema, @NotBlank String reason) {
    }

    public record ProductResponse(UUID id, String productCode, String productName, String serviceType,
                                  String defaultUnit, JsonNode attributesSchema, String status, long version) {
    }

    public record ServiceCreateRequest(@NotBlank String serviceNo, @NotNull UUID customerId, @NotNull UUID companyId,
                                       UUID productId, @NotBlank String serviceName, @NotBlank String serviceType,
                                       String region, String datacenter, String lineName, LocalDate activatedOn,
                                       LocalDate deactivatedOn, @NotBlank String status, JsonNode attributes,
                                       String notes, @NotBlank String reason) {
    }

    public record ServiceUpdateRequest(String serviceName, String region, String datacenter, String lineName,
                                       LocalDate activatedOn, LocalDate deactivatedOn, String status,
                                       JsonNode attributes, String notes, @NotBlank String reason) {
    }

    public record ServiceResponse(UUID id, String serviceNo, UUID customerId, UUID companyId, UUID productId,
                                  String serviceName, String serviceType, String region, String datacenter,
                                  String lineName, LocalDate activatedOn, LocalDate deactivatedOn, String status,
                                  JsonNode attributes, String notes, long version) {
    }

    public record ServiceResourceCreateRequest(@NotBlank String resourceType, @NotBlank String resourceRef,
                                               String displayName, JsonNode attributes, OffsetDateTime effectiveFrom,
                                               OffsetDateTime effectiveTo, @NotBlank String reason) {
    }

    public record ServiceResourceResponse(UUID id, UUID serviceId, String resourceType, String resourceRef,
                                          String displayName, JsonNode attributes, OffsetDateTime effectiveFrom,
                                          OffsetDateTime effectiveTo, String status, long version) {
    }

    public record ContractCreateRequest(@NotBlank String contractNo, @NotNull UUID customerId, @NotNull UUID companyId,
                                        @NotBlank String contractName, @NotNull LocalDate effectiveFrom,
                                        LocalDate effectiveTo, boolean autoRenew, @NotBlank String billingCycle,
                                        @Min(1) @Max(28) Integer billingDay, @PositiveOrZero int paymentTermsDays,
                                        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currencyCode,
                                        BigDecimal taxRate, boolean taxInclusive, String notes,
                                        @NotBlank String reason) {
    }

    public record ContractUpdateRequest(String contractName, LocalDate effectiveFrom, LocalDate effectiveTo,
                                        Boolean autoRenew, @Min(1) @Max(28) Integer billingDay,
                                        @PositiveOrZero Integer paymentTermsDays, BigDecimal taxRate,
                                        Boolean taxInclusive, String notes, @NotBlank String reason) {
    }

    public record ContractResponse(UUID id, String contractNo, UUID customerId, UUID companyId, String contractName,
                                   LocalDate effectiveFrom, LocalDate effectiveTo, boolean autoRenew,
                                   String billingCycle, Integer billingDay, int paymentTermsDays, String currencyCode,
                                   BigDecimal taxRate, boolean taxInclusive, String status, String notes, long version) {
    }

    public record ContractItemCreateRequest(@NotBlank String contractItemNo, @NotNull UUID serviceId,
                                            @NotNull UUID pricingRuleId, @NotBlank String itemName,
                                            @NotBlank String billingType, @NotBlank String billingCycle,
                                            @NotNull OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo,
                                            BigDecimal defaultQuantity, String unit, String taxCategory,
                                            boolean autoBill, boolean visibleOnInvoice, int sortOrder,
                                            @NotBlank String status, JsonNode attributes, @NotBlank String reason) {
    }

    public record ContractItemUpdateRequest(String itemName, OffsetDateTime effectiveTo, BigDecimal defaultQuantity,
                                            Boolean autoBill, Boolean visibleOnInvoice, Integer sortOrder,
                                            String status, JsonNode attributes, @NotBlank String reason) {
    }

    public record ContractItemResponse(UUID id, String contractItemNo, UUID contractId, UUID serviceId,
                                       UUID pricingRuleId, String itemName, String billingType, String billingCycle,
                                       OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo,
                                       BigDecimal defaultQuantity, String unit, String taxCategory, boolean autoBill,
                                       boolean visibleOnInvoice, int sortOrder, String status, JsonNode attributes,
                                       long version) {
    }

    public record ReasonRequest(@NotBlank String reason) {
    }

    private record ActivateCommand(long expectedVersion, String reason) {
    }
}
