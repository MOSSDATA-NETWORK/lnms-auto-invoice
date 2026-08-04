package com.autoinvoice.worker.render;

import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.jobs.BackgroundJob;
import com.autoinvoice.template.TemplateSafetyValidator;
import com.autoinvoice.worker.jobs.JobHandler;
import com.autoinvoice.worker.storage.ObjectStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Component
public class RenderInvoicePdfHandler implements JobHandler {
    public static final String TYPE = "RENDER_INVOICE_PDF";
    private static final String CONTENT_SECURITY_POLICY = "default-src 'none'; "
            + "style-src 'unsafe-inline'; img-src data:; font-src data:; script-src 'none'; "
            + "connect-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'";
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final TemplateSafetyValidator safetyValidator;
    private final PdfRenderer pdfRenderer;
    private final ObjectStorage objectStorage;
    private final RenderedInvoicePersister persister;

    public RenderInvoicePdfHandler(JdbcClient jdbc, ObjectMapper objectMapper,
                                   TemplateSafetyValidator safetyValidator, PdfRenderer pdfRenderer,
                                   ObjectStorage objectStorage, RenderedInvoicePersister persister) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.safetyValidator = safetyValidator;
        this.pdfRenderer = pdfRenderer;
        this.objectStorage = objectStorage;
        this.persister = persister;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public JsonNode handle(BackgroundJob job) throws Exception {
        UUID invoiceId = parseInvoiceId(job.payload());
        InvoiceRenderSource source = load(job.tenantId(), invoiceId);
        if ("CONFIRMED".equals(source.documentStatus())) {
            return existingResult(source);
        }
        if (!"FINALIZING".equals(source.documentStatus())) {
            throw new DomainException("INVOICE_NOT_FINALIZING", "Invoice is not in FINALIZING state", 409,
                    Map.of("invoice_id", invoiceId, "status", source.documentStatus()));
        }

        safetyValidator.validate(source.html(), source.css());
        String renderedHtml = render(source);
        safetyValidator.validateRenderedDocument(renderedHtml);
        PdfRenderer.RenderedPdf renderedPdf = pdfRenderer.render(renderedHtml);
        String sha256 = sha256(renderedPdf.bytes());
        String objectKey = "%s/invoices/%s/%s/%s.pdf".formatted(
                source.tenantId(), source.invoiceId(), source.dataSnapshotHash(), sha256);
        ObjectStorage.StoredObject stored = objectStorage.put(objectKey, renderedPdf.bytes(), "application/pdf");
        UUID fileId = persister.persist(source, stored, renderedPdf.bytes().length, sha256, renderedPdf.chromiumVersion());

        ObjectNode result = objectMapper.createObjectNode();
        result.put("invoice_id", source.invoiceId().toString());
        result.put("file_id", fileId.toString());
        result.put("sha256", sha256);
        result.put("object_key", objectKey);
        return result;
    }

    private String render(InvoiceRenderSource source) throws Exception {
        Map<String, Object> model = objectMapper.convertValue(source.renderModel(), new TypeReference<>() {});
        String body = SafeHandlebarsFactory.render(source.html(), model);
        return document(body, source.css());
    }

    static String document(String body, String css) {
        return "<!doctype html><html><head><meta charset=\"UTF-8\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"" + CONTENT_SECURITY_POLICY + "\">"
                + "<style>" + (css == null ? "" : css)
                + "</style></head><body>" + body + "</body></html>";
    }

    private JsonNode existingResult(InvoiceRenderSource source) {
        return jdbc.sql("""
                        SELECT f.id, f.sha256, f.object_key
                        FROM invoice_files invoice_file
                        JOIN files f ON f.id = invoice_file.file_id AND f.tenant_id = invoice_file.tenant_id
                        WHERE invoice_file.tenant_id = :tenantId AND invoice_file.invoice_id = :invoiceId
                          AND invoice_file.file_role = 'PDF'
                        ORDER BY invoice_file.created_at DESC
                        LIMIT 1
                        """)
                .param("tenantId", source.tenantId())
                .param("invoiceId", source.invoiceId())
                .query((rs, rowNum) -> {
                    ObjectNode result = objectMapper.createObjectNode();
                    result.put("invoice_id", source.invoiceId().toString());
                    result.put("file_id", rs.getObject("id", UUID.class).toString());
                    result.put("sha256", rs.getString("sha256"));
                    result.put("object_key", rs.getString("object_key"));
                    result.put("recovered", true);
                    return (JsonNode) result;
                })
                .optional()
                .orElseThrow(() -> new DomainException("INVOICE_PDF_MISSING",
                        "Confirmed invoice has no persisted PDF", 409, Map.of("invoice_id", source.invoiceId())));
    }

    private InvoiceRenderSource load(UUID tenantId, UUID invoiceId) {
        return jdbc.sql("""
                        SELECT invoice.tenant_id, invoice.id, invoice.invoice_number, invoice.template_version_id,
                               invoice.finalized_by, invoice.data_snapshot_hash, invoice.document_status,
                               invoice.render_model_json, version.html_content, version.css_content
                        FROM invoices invoice
                        JOIN invoice_template_versions version
                          ON version.tenant_id = invoice.tenant_id AND version.id = invoice.template_version_id
                        WHERE invoice.tenant_id = :tenantId AND invoice.id = :invoiceId
                        """)
                .param("tenantId", tenantId)
                .param("invoiceId", invoiceId)
                .query(this::mapSource)
                .optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Invoice was not found", 404,
                        Map.of("invoice_id", invoiceId)));
    }

    private InvoiceRenderSource mapSource(ResultSet rs, int rowNum) throws SQLException {
        try {
            return new InvoiceRenderSource(
                    rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class),
                    rs.getString("invoice_number"), rs.getObject("template_version_id", UUID.class),
                    rs.getObject("finalized_by", UUID.class), rs.getString("data_snapshot_hash"),
                    rs.getString("document_status"), objectMapper.readTree(rs.getString("render_model_json")),
                    rs.getString("html_content"), rs.getString("css_content"));
        } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid invoice render model", exception);
        }
    }

    private UUID parseInvoiceId(JsonNode payload) {
        try {
            return UUID.fromString(payload.path("invoice_id").asText());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("RENDER_INVOICE_PDF payload requires invoice_id", exception);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
