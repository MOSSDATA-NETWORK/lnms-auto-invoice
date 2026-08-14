package com.autoinvoice.api.document;

import com.autoinvoice.api.http.VersionEtag;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.api.storage.ApiObjectStorage;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DocumentController {
    private static final int MAX_TEMPLATE_BYTES = 8 * 1024 * 1024;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final ApiObjectStorage objectStorage;
    private final ContractDocumentRenderer contractRenderer = new ContractDocumentRenderer();
    private final InvoiceExcelRenderer excelRenderer = new InvoiceExcelRenderer();

    public DocumentController(JdbcClient jdbc, ObjectMapper objectMapper, ApiObjectStorage objectStorage) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.objectStorage = objectStorage;
    }

    @PostMapping(value = "/contracts/{id}/template", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('contract.write','system.admin')")
    @Transactional
    public ResponseEntity<RenderedFile> uploadContractTemplate(
            Authentication authentication, @PathVariable UUID id,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestParam("file") MultipartFile multipart, HttpServletRequest servletRequest) throws Exception {
        AuthenticatedUser actor = principal(authentication);
        requireContractVersion(actor.tenantId(), id, VersionEtag.parse(ifMatch));
        byte[] bytes = multipart.getBytes();
        if (bytes.length > MAX_TEMPLATE_BYTES || !multipart.getOriginalFilename().toLowerCase().endsWith(".docx")) {
            throw new DomainException("TEMPLATE_INVALID", "Contract template must be a docx under 8 MiB", 422, Map.of());
        }
        RenderedFile file = store(actor, bytes, safeFilename(multipart.getOriginalFilename()), "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        jdbc.sql("""
                        UPDATE contracts SET template_file_id = :fileId, updated_at = now(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("fileId", file.id()).param("tenantId", actor.tenantId()).param("id", id).update();
        return ResponseEntity.ok().body(file);
    }

    @PostMapping("/contracts/{id}/render")
    @PreAuthorize("hasAnyAuthority('contract.write','system.admin')")
    public ResponseEntity<RenderedFile> renderContract(
            Authentication authentication, @PathVariable UUID id,
            @RequestBody(required = false) ContractRenderRequest request,
            HttpServletRequest servletRequest) throws Exception {
        AuthenticatedUser actor = principal(authentication);
        ContractTemplate contract = findContractTemplate(actor.tenantId(), id);
        UUID templateFileId = request != null && request.templateId() != null
                ? requireDocumentTemplateFile(actor.tenantId(), request.templateId(), "CONTRACT_DOCX")
                : contract.templateFileId();
        byte[] template = load(actor.tenantId(), templateFileId);
        JsonNode model = contractModel(actor.tenantId(), contract,
                request != null ? request.billingEntityId() : null);
        byte[] filled = contractRenderer.render(template, new PlaceholderResolver(toRoots(model)));
        RenderedFile file = store(actor, filled, contract.contractNo() + "-合同.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        return ResponseEntity.ok().body(file);
    }

    @PostMapping(value = "/invoice-profiles/{id}/excel-template", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('contract.write','template.publish','system.admin')")
    @Transactional
    public ResponseEntity<RenderedFile> uploadExcelTemplate(
            Authentication authentication, @PathVariable UUID id,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestParam("file") MultipartFile multipart, HttpServletRequest servletRequest) throws Exception {
        AuthenticatedUser actor = principal(authentication);
        requireProfileVersion(actor.tenantId(), id, VersionEtag.parse(ifMatch));
        byte[] bytes = multipart.getBytes();
        if (bytes.length > MAX_TEMPLATE_BYTES || !multipart.getOriginalFilename().toLowerCase().endsWith(".xlsx")) {
            throw new DomainException("TEMPLATE_INVALID", "Invoice template must be an xlsx under 8 MiB", 422, Map.of());
        }
        RenderedFile file = store(actor, bytes, safeFilename(multipart.getOriginalFilename()),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        jdbc.sql("""
                        UPDATE invoice_profiles SET excel_template_file_id = :fileId, updated_at = now(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("fileId", file.id()).param("tenantId", actor.tenantId()).param("id", id).update();
        return ResponseEntity.ok().body(file);
    }

    @PostMapping("/invoices/{id}/render-excel")
    @PreAuthorize("hasAnyAuthority('invoice.finalize','invoice.send','audit.read','system.admin')")
    public ResponseEntity<RenderedFile> renderInvoiceExcel(
            Authentication authentication, @PathVariable UUID id, HttpServletRequest servletRequest) throws Exception {
        AuthenticatedUser actor = principal(authentication);
        InvoiceTemplate invoice = findInvoiceRenderSource(actor.tenantId(), id);
        UUID templateFileId = invoice.documentTemplateId() != null
                ? requireDocumentTemplateFile(actor.tenantId(), invoice.documentTemplateId(), "INVOICE_XLSX")
                : invoice.excelTemplateFileId();
        if (templateFileId == null) {
            throw new DomainException("TEMPLATE_REQUIRED",
                    "Invoice has no Excel template; select one in 账单配置 from the template center", 422, Map.of("invoice_id", id));
        }
        byte[] template = load(actor.tenantId(), templateFileId);
        ObjectNode root = (ObjectNode) objectMapper.readTree(invoice.renderModelJson());
        ObjectNode invoiceNode = root.has("invoice") && root.get("invoice").isObject()
                ? (ObjectNode) root.get("invoice") : root.putObject("invoice");
        invoiceNode.put("invoice_number", invoice.invoiceNumber());
        invoiceNode.put("number", invoice.invoiceNumber());
        invoiceNode.put("total_minor", invoice.totalMinor());
        invoiceNode.put("subtotal_minor", invoice.subtotalMinor());
        invoiceNode.put("tax_minor", invoice.taxMinor());
        invoiceNode.put("discount_minor", invoice.discountMinor());
        byte[] filled = excelRenderer.render(template, new PlaceholderResolver(toRoots(root)));
        RenderedFile file = store(actor, filled, invoice.invoiceNumber() + ".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        return ResponseEntity.ok().body(file);
    }

    private Map<String, JsonNode> toRoots(JsonNode model) {
        Map<String, JsonNode> roots = new LinkedHashMap<>();
        model.fields().forEachRemaining(entry -> roots.put(entry.getKey(), entry.getValue()));
        return roots;
    }

    private JsonNode contractModel(UUID tenantId, ContractTemplate contract, UUID billingEntityId) {
        ObjectNode model = objectMapper.createObjectNode();
        model.set("customer", jdbc.sql("""
                        SELECT customer_no, customer_name, customer_type FROM customers
                        WHERE tenant_id = :tenantId AND id = :id
                        """).param("tenantId", tenantId).param("id", contract.customerId())
                .query((rs, row) -> node(mapRow(rs, "customer_no", "customer_name", "customer_type"))).single());
        model.set("company", jdbc.sql("""
                        SELECT company_code, company_name, company_name_en, address, tax_number, invoice_title,
                               phone, bank_name, bank_account, invoice_type, br_number
                        FROM companies WHERE tenant_id = :tenantId AND id = :id
                        """).param("tenantId", tenantId).param("id", contract.companyId())
                .query((rs, row) -> node(mapRow(rs, "company_code", "company_name", "company_name_en",
                        "address", "tax_number", "invoice_title", "phone", "bank_name", "bank_account", "invoice_type", "br_number"))).single());
        ObjectNode contractNode = objectMapper.createObjectNode();
        contractNode.put("contract_no", contract.contractNo());
        contractNode.put("contract_name", contract.contractName());
        contractNode.put("effective_from", contract.effectiveFrom() == null ? "" : contract.effectiveFrom().toString());
        contractNode.put("effective_to", contract.effectiveTo() == null ? "" : contract.effectiveTo().toString());
        contractNode.put("billing_cycle", contract.billingCycle() == null ? "" : contract.billingCycle());
        contractNode.put("currency_code", contract.currencyCode() == null ? "" : contract.currencyCode());
        contractNode.put("auto_renew", String.valueOf(contract.autoRenew()));
        contractNode.put("status", contract.status());
        model.set("contract", contractNode);
        model.set("seller", sellerModel(tenantId, billingEntityId));
        return model;
    }

    private JsonNode sellerModel(UUID tenantId, UUID entityId) {
        if (entityId != null) {
            return jdbc.sql("""
                            SELECT entity_code, entity_name, entity_name_en, country_region, address, phone,
                                   tax_number, br_number, invoice_title, bank_name, bank_code, swift_code,
                                   bank_address, bank_account, default_currency
                            FROM billing_entities
                            WHERE tenant_id = :tenantId AND id = :entityId AND status = 'ACTIVE'
                            """).param("tenantId", tenantId).param("entityId", entityId)
                    .query((rs, row) -> node(mapRow(rs, "entity_code", "entity_name", "entity_name_en",
                            "country_region", "address", "phone", "tax_number", "br_number", "invoice_title", "bank_name",
                            "bank_code", "swift_code", "bank_address", "bank_account", "default_currency")))
                    .stream().findFirst().orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND",
                            "Billing entity was not found or is not active", 404, Map.of("entity_id", entityId)));
        }
        return jdbc.sql("""
                        SELECT entity_code, entity_name, entity_name_en, country_region, address, phone,
                               tax_number, br_number, invoice_title, bank_name, bank_code, swift_code,
                               bank_address, bank_account, default_currency
                        FROM billing_entities
                        WHERE tenant_id = :tenantId AND status = 'ACTIVE'
                        ORDER BY created_at LIMIT 1
                        """).param("tenantId", tenantId)
                .query((rs, row) -> node(mapRow(rs, "entity_code", "entity_name", "entity_name_en",
                        "country_region", "address", "phone", "tax_number", "br_number", "invoice_title", "bank_name",
                        "bank_code", "swift_code", "bank_address", "bank_account", "default_currency")))
                .stream().findFirst().orElseGet(objectMapper::createObjectNode);
    }

    private JsonNode node(Map<String, String> values) {
        return objectMapper.valueToTree(values);
    }

    private Map<String, String> mapRow(ResultSet rs, String... columns) throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String column : columns) {
            values.put(column, rs.getString(column) == null ? "" : rs.getString(column));
        }
        return values;
    }

    private UUID requireDocumentTemplateFile(UUID tenantId, UUID templateId, String expectedType) {
        return jdbc.sql("""
                        SELECT file_id FROM document_templates
                        WHERE tenant_id = :tenantId AND id = :id AND template_type = :type AND status = 'ACTIVE'
                        """).param("tenantId", tenantId).param("id", templateId).param("type", expectedType)
                .query(UUID.class).optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND",
                        "Document template was not found or has the wrong type", 404,
                        Map.of("template_id", templateId, "type", expectedType)));
    }

    private byte[] load(UUID tenantId, UUID fileId) {
        StoredKey stored = jdbc.sql("""
                        SELECT object_key, bucket_name FROM files
                        WHERE tenant_id = :tenantId AND id = :fileId
                        """).param("tenantId", tenantId).param("fileId", fileId)
                .query((rs, row) -> new StoredKey(rs.getString("bucket_name"), rs.getString("object_key")))
                .optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Template file was not found", 404, Map.of("file_id", fileId)));
        try {
            return objectStorage.get(stored.bucket(), stored.key());
        } catch (Exception exception) {
            throw new DomainException("RESOURCE_NOT_FOUND", "Template object is unavailable", 404, Map.of("file_id", fileId));
        }
    }

    private RenderedFile store(AuthenticatedUser actor, byte[] bytes, String filename, String mimeType) throws Exception {
        String sha256 = sha256(bytes);
        String objectKey = actor.tenantId() + "/documents/" + actor.userId() + "/" + sha256 + "/" + filename;
        ApiObjectStorage.StoredObject stored = objectStorage.put(objectKey, bytes, mimeType);
        UUID candidate = UuidV7.generate();
        jdbc.sql("""
                        INSERT INTO files(
                            id, tenant_id, storage_provider, bucket_name, object_key,
                            original_filename, mime_type, file_size, sha256, created_by
                        ) VALUES (
                            :id, :tenantId, :provider, :bucket, :objectKey,
                            :filename, :mimeType, :size, :sha256, :createdBy
                        ) ON CONFLICT (tenant_id, bucket_name, object_key) DO NOTHING
                        """)
                .param("id", candidate).param("tenantId", actor.tenantId())
                .param("provider", stored.provider()).param("bucket", stored.bucket())
                .param("objectKey", stored.objectKey()).param("filename", filename)
                .param("mimeType", mimeType).param("size", bytes.length).param("sha256", sha256)
                .param("createdBy", actor.userId()).update();
        UUID id = jdbc.sql("""
                        SELECT id FROM files
                        WHERE tenant_id = :tenantId AND bucket_name = :bucket AND object_key = :objectKey
                        """)
                .param("tenantId", actor.tenantId()).param("bucket", stored.bucket())
                .param("objectKey", stored.objectKey()).query(UUID.class).single();
        return new RenderedFile(id, filename, mimeType, bytes.length, sha256);
    }

    private ContractTemplate findContractTemplate(UUID tenantId, UUID contractId) {
        return jdbc.sql("""
                        SELECT id, contract_no, contract_name, customer_id, company_id, template_file_id,
                               effective_from, effective_to, billing_cycle, currency_code, auto_renew, status
                        FROM contracts WHERE tenant_id = :tenantId AND id = :id
                        """).param("tenantId", tenantId).param("id", contractId)
                .query((rs, row) -> new ContractTemplate(rs.getObject("id", UUID.class),
                        rs.getString("contract_no"), rs.getString("contract_name"),
                        rs.getObject("customer_id", UUID.class), rs.getObject("company_id", UUID.class),
                        rs.getObject("template_file_id", UUID.class),
                        rs.getObject("effective_from", java.time.LocalDate.class),
                        rs.getObject("effective_to", java.time.LocalDate.class),
                        rs.getString("billing_cycle"), rs.getString("currency_code"),
                        rs.getBoolean("auto_renew"), rs.getString("status")))
                .optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Contract was not found", 404, Map.of("contract_id", contractId)));
    }

    private InvoiceTemplate findInvoiceRenderSource(UUID tenantId, UUID invoiceId) {
        return jdbc.sql("""
                        SELECT invoice.invoice_number, invoice.render_model_json,
                               invoice.subtotal_minor, invoice.discount_minor,
                               invoice.tax_minor, invoice.total_minor,
                               profile.excel_template_file_id, profile.document_template_id
                        FROM invoices invoice
                        LEFT JOIN invoice_profiles profile
                          ON profile.tenant_id = invoice.tenant_id AND profile.id = invoice.invoice_profile_id
                        WHERE invoice.tenant_id = :tenantId AND invoice.id = :id
                        """).param("tenantId", tenantId).param("id", invoiceId)
                .query((rs, row) -> new InvoiceTemplate(rs.getString("invoice_number"),
                        rs.getString("render_model_json"), rs.getObject("excel_template_file_id", UUID.class),
                        rs.getObject("document_template_id", UUID.class),
                        rs.getLong("subtotal_minor"), rs.getLong("discount_minor"),
                        rs.getLong("tax_minor"), rs.getLong("total_minor")))
                .optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Invoice was not found", 404, Map.of("invoice_id", invoiceId)));
    }

    private void requireContractVersion(UUID tenantId, UUID id, long version) {
        Long current = jdbc.sql("SELECT version FROM contracts WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(Long.class).optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Contract was not found", 404, Map.of()));
        if (current != version) {
            throw new DomainException("VERSION_CONFLICT", "Contract was modified by another request", 409, Map.of("expected_version", version));
        }
    }

    private void requireProfileVersion(UUID tenantId, UUID id, long version) {
        Long current = jdbc.sql("SELECT version FROM invoice_profiles WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(Long.class).optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Invoice profile was not found", 404, Map.of()));
        if (current != version) {
            throw new DomainException("VERSION_CONFLICT", "Invoice profile was modified by another request", 409, Map.of("expected_version", version));
        }
    }

    private AuthenticatedUser principal(Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    private String safeFilename(String name) {
        return name == null ? "document" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replace("..", "_");
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record RenderedFile(UUID id, String filename, String mimeType, int size, String sha256) {
    }

    private record StoredKey(String bucket, String key) {
    }

    private record ContractTemplate(UUID id, String contractNo, String contractName, UUID customerId,
                                    UUID companyId, UUID templateFileId, java.time.LocalDate effectiveFrom,
                                    java.time.LocalDate effectiveTo, String billingCycle, String currencyCode,
                                    boolean autoRenew, String status) {
    }

    private record InvoiceTemplate(String invoiceNumber, String renderModelJson, UUID excelTemplateFileId,
                                   UUID documentTemplateId, long subtotalMinor, long discountMinor,
                                   long taxMinor, long totalMinor) {
    }

    public record ContractRenderRequest(UUID templateId, UUID billingEntityId) {
    }
}
