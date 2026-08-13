package com.autoinvoice.api.billing;

import com.autoinvoice.api.http.RequestIdFilter;
import com.autoinvoice.api.http.VersionEtag;
import com.autoinvoice.api.idempotency.IdempotencyExecutor;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.audit.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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

import jakarta.servlet.http.HttpServletRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/billing-entities")
public class BillingEntityController {
    private final JdbcClient jdbc;
    private final IdempotencyExecutor idempotency;
    private final AuditService audit;

    public BillingEntityController(JdbcClient jdbc, IdempotencyExecutor idempotency, AuditService audit) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('customer.read','contract.write','preview.generate','system.admin')")
    public List<EntityResponse> list(Authentication authentication,
                                     @RequestParam(required = false) String status) {
        UUID tenantId = principal(authentication).tenantId();
        return jdbc.sql("""
                        SELECT * FROM billing_entities
                        WHERE tenant_id = :tenantId
                          AND (CAST(:status AS varchar) IS NULL OR status = :status)
                        ORDER BY entity_code
                        """)
                .param("tenantId", tenantId).param("status", blank(status))
                .query(this::mapEntity).list();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system.admin')")
    public ResponseEntity<EntityResponse> create(Authentication authentication,
                                                 @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                 @Valid @RequestBody EntityCreateRequest request,
                                                 HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST",
                "/api/v1/billing-entities", request, EntityResponse.class, () -> {
                    UUID id = UuidV7.generate();
                    jdbc.sql("""
                                    INSERT INTO billing_entities(
                                        id, tenant_id, entity_code, entity_name, entity_name_en,
                                        country_region, address, phone, tax_number, br_number, invoice_title,
                                        bank_name, bank_code, swift_code, bank_address, bank_account,
                                        default_currency, status
                                    ) VALUES (
                                        :id, :tenantId, :code, :name, :nameEn,
                                        :region, :address, :phone, :taxNumber, :brNumber, :invoiceTitle,
                                        :bankName, :bankCode, :swiftCode, :bankAddress, :bankAccount,
                                        :currency, 'ACTIVE'
                                    )
                                    """)
                            .param("id", id).param("tenantId", actor.tenantId())
                            .param("code", request.entityCode()).param("name", request.entityName())
                            .param("nameEn", request.entityNameEn()).param("region", request.countryRegion())
                            .param("address", request.address()).param("phone", request.phone())
                            .param("taxNumber", request.taxNumber()).param("brNumber", request.brNumber())
                            .param("invoiceTitle", request.invoiceTitle()).param("bankName", request.bankName())
                            .param("bankCode", request.bankCode()).param("swiftCode", request.swiftCode())
                            .param("bankAddress", request.bankAddress()).param("bankAccount", request.bankAccount())
                            .param("currency", request.defaultCurrency()).update();
                    EntityResponse created = findEntity(actor.tenantId(), id);
                    record(actor, "billing_entity.created", id, null, created, request.reason(), servletRequest);
                    return ResponseEntity.status(HttpStatus.CREATED)
                            .eTag(VersionEtag.format(0)).body(created);
                });
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('system.admin')")
    @Transactional
    public ResponseEntity<EntityResponse> update(Authentication authentication, @PathVariable UUID id,
                                                 @RequestHeader(org.springframework.http.HttpHeaders.IF_MATCH) String ifMatch,
                                                 @Valid @RequestBody EntityUpdateRequest request,
                                                 HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        EntityResponse before = findEntity(actor.tenantId(), id);
        long version = VersionEtag.parse(ifMatch);
        int changed = jdbc.sql("""
                        UPDATE billing_entities SET
                            entity_name = COALESCE(:name, entity_name),
                            entity_name_en = COALESCE(:nameEn, entity_name_en),
                            country_region = COALESCE(:region, country_region),
                            address = COALESCE(:address, address),
                            phone = COALESCE(:phone, phone),
                            tax_number = COALESCE(:taxNumber, tax_number),
                            br_number = COALESCE(:brNumber, br_number),
                            invoice_title = COALESCE(:invoiceTitle, invoice_title),
                            bank_name = COALESCE(:bankName, bank_name),
                            bank_code = COALESCE(:bankCode, bank_code),
                            swift_code = COALESCE(:swiftCode, swift_code),
                            bank_address = COALESCE(:bankAddress, bank_address),
                            bank_account = COALESCE(:bankAccount, bank_account),
                            default_currency = COALESCE(:currency, default_currency),
                            status = COALESCE(:status, status), updated_at = now(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :id AND version = :version
                        """)
                .param("name", request.entityName()).param("nameEn", request.entityNameEn())
                .param("region", request.countryRegion()).param("address", request.address())
                .param("phone", request.phone()).param("taxNumber", request.taxNumber())
                .param("brNumber", request.brNumber()).param("invoiceTitle", request.invoiceTitle())
                .param("bankName", request.bankName()).param("bankCode", request.bankCode())
                .param("swiftCode", request.swiftCode()).param("bankAddress", request.bankAddress())
                .param("bankAccount", request.bankAccount()).param("currency", request.defaultCurrency())
                .param("status", request.status()).param("tenantId", actor.tenantId())
                .param("id", id).param("version", version).update();
        if (changed != 1) {
            throw new DomainException("VERSION_CONFLICT", "Billing entity was modified by another request", 409,
                    Map.of("expected_version", version));
        }
        EntityResponse after = findEntity(actor.tenantId(), id);
        record(actor, "billing_entity.updated", id, before, after, request.reason(), servletRequest);
        return ResponseEntity.ok().eTag(VersionEtag.format(after.version())).body(after);
    }

    private EntityResponse findEntity(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM billing_entities WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::mapEntity).optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Billing entity was not found", 404,
                        Map.of("billing_entity_id", id)));
    }

    private EntityResponse mapEntity(ResultSet rs, int row) throws SQLException {
        return new EntityResponse(rs.getObject("id", UUID.class), rs.getString("entity_code"),
                rs.getString("entity_name"), rs.getString("entity_name_en"), rs.getString("country_region"),
                rs.getString("address"), rs.getString("phone"), rs.getString("tax_number"),
                rs.getString("br_number"), rs.getString("invoice_title"), rs.getString("bank_name"),
                rs.getString("bank_code"), rs.getString("swift_code"), rs.getString("bank_address"),
                rs.getString("bank_account"), rs.getString("default_currency"),
                rs.getString("status"), rs.getLong("version"));
    }

    private AuthenticatedUser principal(Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void record(AuthenticatedUser actor, String action, UUID id, Object before, Object after,
                        String reason, HttpServletRequest request) {
        audit.record(actor.tenantId(), "USER", actor.userId(), actor.displayName(), action,
                "billing_entity", id, before, after, reason, request.getHeader(RequestIdFilter.HEADER));
    }

    public record EntityCreateRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{2,99}") String entityCode,
            @NotBlank String entityName, String entityNameEn, String countryRegion,
            String address, String phone, String taxNumber, String brNumber, String invoiceTitle,
            String bankName, String bankCode, String swiftCode, String bankAddress, String bankAccount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String defaultCurrency, @NotBlank String reason) {
    }

    public record EntityUpdateRequest(
            String entityName, String entityNameEn, String countryRegion,
            String address, String phone, String taxNumber, String brNumber, String invoiceTitle,
            String bankName, String bankCode, String swiftCode, String bankAddress, String bankAccount,
            @Pattern(regexp = "[A-Z]{3}") String defaultCurrency,
            @Pattern(regexp = "ACTIVE|DISABLED") String status, @NotBlank String reason) {
    }

    public record EntityResponse(UUID id, String entityCode, String entityName, String entityNameEn,
                                 String countryRegion, String address, String phone, String taxNumber,
                                 String brNumber, String invoiceTitle, String bankName, String bankCode,
                                 String swiftCode, String bankAddress, String bankAccount,
                                 String defaultCurrency, String status, long version) {
    }
}
