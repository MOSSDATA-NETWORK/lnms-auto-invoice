package com.autoinvoice.notification;

import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.jobs.BackgroundJobService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class NotificationService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final BackgroundJobService jobs;

    public NotificationService(JdbcClient jdbc, ObjectMapper objectMapper, BackgroundJobService jobs) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.jobs = jobs;
    }

    @Transactional
    public QueueResult queueInvoice(UUID tenantId, UUID invoiceId, List<String> emails,
                                    List<UUID> webhookEndpointIds, long expectedVersion) {
        Invoice invoice = loadInvoice(tenantId, invoiceId, expectedVersion);
        List<QueuedDelivery> deliveries = new ArrayList<>();
        List<String> normalizedEmails = emails == null ? List.of() : emails;
        List<UUID> normalizedEndpoints = webhookEndpointIds == null ? List.of() : webhookEndpointIds;

        for (String email : normalizedEmails.stream().map(String::trim)
                .filter(value -> !value.isBlank()).distinct().toList()) {
            if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                throw new DomainException("NOTIFICATION_RECIPIENT_INVALID", "Email recipient is invalid", 422,
                        Map.of("recipient", email));
            }
            deliveries.add(queue(tenantId, invoice, "EMAIL", email, null));
        }
        for (UUID endpointId : normalizedEndpoints.stream().distinct().toList()) {
            String endpointCode = jdbc.sql("""
                            SELECT endpoint_code FROM webhook_endpoints
                            WHERE tenant_id = :tenantId AND id = :id AND status = 'ACTIVE'
                              AND (event_types_json = '[]'::jsonb OR event_types_json ? 'invoice.confirmed')
                            """)
                    .param("tenantId", tenantId).param("id", endpointId).query(String.class).optional()
                    .orElseThrow(() -> new DomainException("WEBHOOK_ENDPOINT_UNAVAILABLE",
                            "Webhook endpoint is missing, disabled or does not subscribe to invoice.confirmed", 422,
                            Map.of("webhook_endpoint_id", endpointId)));
            deliveries.add(queue(tenantId, invoice, "WEBHOOK", endpointCode, endpointId));
        }
        if (deliveries.isEmpty()) {
            throw new DomainException("NOTIFICATION_RECIPIENT_REQUIRED",
                    "At least one email or webhook recipient is required", 422, Map.of());
        }
        long version = jdbc.sql("""
                        UPDATE invoices
                        SET send_status = CASE WHEN send_status = 'SENT' THEN send_status ELSE 'QUEUED' END,
                            updated_at = clock_timestamp(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :invoiceId
                        RETURNING version
                        """)
                .param("tenantId", tenantId).param("invoiceId", invoiceId).query(Long.class).single();
        return new QueueResult(invoiceId, List.copyOf(deliveries), "QUEUED", version);
    }

    @Transactional
    public Optional<QueueResult> queueAutomaticInvoice(UUID tenantId, UUID invoiceId) {
        AutomaticDelivery source = jdbc.sql("""
                        SELECT invoice.version, invoice.profile_snapshot_json,
                               COALESCE(settings.auto_send_enabled, false) AS auto_send_enabled,
                               COALESCE(settings.emergency_stop, false) AS emergency_stop
                        FROM invoices invoice
                        LEFT JOIN tenant_operational_settings settings ON settings.tenant_id = invoice.tenant_id
                        WHERE invoice.tenant_id = :tenantId AND invoice.id = :invoiceId
                          AND invoice.document_status IN ('CONFIRMED', 'SENT')
                        """)
                .param("tenantId", tenantId).param("invoiceId", invoiceId)
                .query((rs, row) -> new AutomaticDelivery(rs.getLong("version"),
                        rs.getString("profile_snapshot_json"), rs.getBoolean("auto_send_enabled"),
                        rs.getBoolean("emergency_stop"))).optional().orElse(null);
        if (source == null || !source.autoSendEnabled() || source.emergencyStop()) {
            return Optional.empty();
        }
        JsonNode profile;
        try {
            profile = objectMapper.readTree(source.profileSnapshot());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted invoice profile snapshot is invalid", exception);
        }
        if (!profile.path("auto_send").asBoolean(false)) {
            return Optional.empty();
        }
        List<String> emails = new ArrayList<>();
        JsonNode recipients = profile.path("recipients");
        if (recipients.isArray()) {
            recipients.forEach(recipient -> {
                if (recipient.isTextual()) {
                    emails.add(recipient.asText());
                    return;
                }
                if (recipient.isObject() && recipient.path("enabled").asBoolean(true)
                        && "EMAIL".equalsIgnoreCase(recipient.path("channel").asText("EMAIL"))) {
                    String value = recipient.path("email").asText(recipient.path("value").asText(""));
                    if (!value.isBlank()) {
                        emails.add(value);
                    }
                }
            });
        }
        List<UUID> endpointIds = jdbc.sql("""
                        SELECT id FROM webhook_endpoints
                        WHERE tenant_id = :tenantId AND status = 'ACTIVE'
                          AND (event_types_json = '[]'::jsonb OR event_types_json ? 'invoice.confirmed')
                        ORDER BY endpoint_code
                        """)
                .param("tenantId", tenantId).query(UUID.class).list();
        if (emails.isEmpty() && endpointIds.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(queueInvoice(tenantId, invoiceId, emails, endpointIds, source.version()));
    }

    private QueuedDelivery queue(UUID tenantId, Invoice invoice, String channel,
                                 String recipient, UUID endpointId) {
        String deduplicationKey = "invoice.confirmed:" + invoice.id() + ":" + channel + ":"
                + (endpointId == null ? recipient.toLowerCase() : endpointId) + ":" + invoice.dataHash();
        UUID candidate = UuidV7.generate();
        ObjectNode payload = objectMapper.createObjectNode()
                .put("event_id", candidate.toString())
                .put("event_type", "invoice.confirmed")
                .put("occurred_at", OffsetDateTime.now().toString())
                .put("tenant_id", tenantId.toString())
                .put("resource_id", invoice.id().toString())
                .put("data_version", invoice.version())
                .put("invoice_id", invoice.id().toString())
                .put("invoice_number", invoice.number())
                .put("currency_code", invoice.currency())
                .put("total_minor", invoice.totalMinor())
                .put("issue_date", invoice.issueDate())
                .put("due_date", invoice.dueDate())
                .put("data_snapshot_hash", invoice.dataHash())
                .put("pdf_file_id", invoice.pdfFileId().toString())
                .put("pdf_bucket", invoice.pdfBucket())
                .put("pdf_object_key", invoice.pdfObjectKey())
                .put("pdf_sha256", invoice.pdfSha256())
                .put("subject", "账单 " + invoice.number())
                .put("body", "正式账单 " + invoice.number() + " 已生成，应收 "
                        + invoice.currency() + " " + invoice.totalMinor() + "（最小货币单位）。");
        if (endpointId != null) {
            payload.put("webhook_endpoint_id", endpointId.toString());
        }
        jdbc.sql("""
                        INSERT INTO notification_logs(
                            id, tenant_id, invoice_id, channel, event_type, recipient,
                            deduplication_key, subject_rendered, payload_json, status, next_attempt_at
                        ) VALUES (
                            :id, :tenantId, :invoiceId, :channel, 'invoice.confirmed', :recipient,
                            :deduplicationKey, :subject, CAST(:payload AS jsonb), 'PENDING', now()
                        ) ON CONFLICT (tenant_id, deduplication_key) DO NOTHING
                        """)
                .param("id", candidate).param("tenantId", tenantId).param("invoiceId", invoice.id())
                .param("channel", channel).param("recipient", recipient).param("deduplicationKey", deduplicationKey)
                .param("subject", payload.path("subject").asText()).param("payload", payload.toString()).update();
        UUID notificationId = jdbc.sql("""
                        SELECT id FROM notification_logs
                        WHERE tenant_id = :tenantId AND deduplication_key = :deduplicationKey
                        """)
                .param("tenantId", tenantId).param("deduplicationKey", deduplicationKey)
                .query(UUID.class).single();
        JsonNode jobPayload = objectMapper.createObjectNode().put("notification_id", notificationId.toString());
        UUID jobId = jobs.enqueue(tenantId, "SEND_NOTIFICATION", "notification:" + notificationId, jobPayload);
        return new QueuedDelivery(notificationId, jobId, channel, recipient);
    }

    private Invoice loadInvoice(UUID tenantId, UUID invoiceId, long expectedVersion) {
        return jdbc.sql("""
                        SELECT invoice.id, invoice.invoice_number, invoice.currency_code, invoice.total_minor,
                               invoice.issue_date::text, invoice.due_date::text, invoice.data_snapshot_hash,
                               invoice.document_status, invoice.version, file.id AS file_id,
                               file.bucket_name, file.object_key, file.sha256
                        FROM invoices invoice
                        JOIN invoice_files invoice_file ON invoice_file.tenant_id = invoice.tenant_id
                             AND invoice_file.invoice_id = invoice.id AND invoice_file.file_role = 'PDF'
                        JOIN files file ON file.tenant_id = invoice_file.tenant_id AND file.id = invoice_file.file_id
                        WHERE invoice.tenant_id = :tenantId AND invoice.id = :invoiceId
                        ORDER BY invoice_file.created_at DESC LIMIT 1 FOR UPDATE OF invoice
                        """)
                .param("tenantId", tenantId).param("invoiceId", invoiceId).query(this::mapInvoice).optional()
                .orElseThrow(() -> new DomainException("INVOICE_PDF_NOT_READY",
                        "Confirmed invoice PDF is required before notification", 409, Map.of("invoice_id", invoiceId)))
                .requireVersion(expectedVersion);
    }

    private Invoice mapInvoice(ResultSet rs, int row) throws SQLException {
        String status = rs.getString("document_status");
        if (!List.of("CONFIRMED", "SENT").contains(status)) {
            throw new DomainException("INVOICE_NOT_SENDABLE", "Invoice is not confirmed for delivery", 409,
                    Map.of("document_status", status));
        }
        return new Invoice(rs.getObject("id", UUID.class), rs.getString("invoice_number"),
                rs.getString("currency_code"), rs.getLong("total_minor"), rs.getString("issue_date"),
                rs.getString("due_date"), rs.getString("data_snapshot_hash"), rs.getObject("file_id", UUID.class),
                rs.getString("bucket_name"), rs.getString("object_key"), rs.getString("sha256"),
                rs.getLong("version"));
    }

    public record QueueResult(UUID invoiceId, List<QueuedDelivery> deliveries, String sendStatus, long version) {
    }

    public record QueuedDelivery(UUID notificationId, UUID jobId, String channel, String recipient) {
    }

    private record Invoice(UUID id, String number, String currency, long totalMinor,
                           String issueDate, String dueDate, String dataHash, UUID pdfFileId,
                           String pdfBucket, String pdfObjectKey, String pdfSha256, long version) {
        private Invoice requireVersion(long expectedVersion) {
            if (version != expectedVersion) {
                throw new DomainException("VERSION_CONFLICT", "Invoice was modified by another request", 409,
                        Map.of("expected_version", expectedVersion, "current_version", version));
            }
            return this;
        }
    }

    private record AutomaticDelivery(long version, String profileSnapshot,
                                     boolean autoSendEnabled, boolean emergencyStop) {
    }
}
