package com.autoinvoice.worker.notification;

import com.autoinvoice.notification.WebhookUrlPolicy;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.jobs.BackgroundJob;
import com.autoinvoice.platform.security.SecretCipher;
import com.autoinvoice.worker.jobs.JobHandler;
import com.autoinvoice.worker.storage.ObjectStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.mail.internet.MimeMessage;
import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Component
public class SendNotificationHandler implements JobHandler {
    public static final String TYPE = "SEND_NOTIFICATION";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final JavaMailSender mailSender;
    private final ObjectStorage objectStorage;
    private final SecretCipher secretCipher;
    private final WebhookUrlPolicy urlPolicy;
    private final OkHttpClient webhookClient;
    private final String emailFrom;

    public SendNotificationHandler(JdbcClient jdbc, ObjectMapper objectMapper, JavaMailSender mailSender,
                                   ObjectStorage objectStorage, SecretCipher secretCipher,
                                   WebhookUrlPolicy urlPolicy,
                                   @Value("${auto-invoice.notification.email-from:no-reply@auto-invoice.local}") String emailFrom,
                                   @Value("${auto-invoice.notification.webhook-connect-timeout:10s}") Duration connectTimeout,
                                   @Value("${auto-invoice.notification.webhook-read-timeout:15s}") Duration readTimeout) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.mailSender = mailSender;
        this.objectStorage = objectStorage;
        this.secretCipher = secretCipher;
        this.urlPolicy = urlPolicy;
        this.emailFrom = emailFrom;
        Dns safeDns = hostname -> urlPolicy.resolvePublic(hostname);
        this.webhookClient = new OkHttpClient.Builder()
                .dns(safeDns)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .callTimeout(connectTimeout.plus(readTimeout))
                .build();
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public JsonNode handle(BackgroundJob job) throws Exception {
        UUID notificationId = parseNotificationId(job.payload());
        Delivery delivery = load(job.tenantId(), notificationId);
        return switch (delivery.channel()) {
            case "EMAIL" -> handleEmail(job, delivery);
            case "WEBHOOK" -> handleWebhook(job, delivery);
            default -> {
                DomainException exception = new DomainException("NOTIFICATION_CHANNEL_UNSUPPORTED",
                        "Notification channel is unsupported", 422, Map.of("channel", delivery.channel()));
                markFailed(delivery, job, exception);
                reconcileInvoice(job.tenantId(), delivery.invoiceId());
                throw exception;
            }
        };
    }

    private JsonNode handleEmail(BackgroundJob job, Delivery delivery) throws Exception {
        NotificationDeliveryPolicy.EmailAction action = NotificationDeliveryPolicy.emailAction(delivery.status());
        if (action == NotificationDeliveryPolicy.EmailAction.RETURN_SENT) {
            reconcileInvoice(job.tenantId(), delivery.invoiceId());
            return result(delivery, delivery.providerMessageId(), true, false);
        }
        if (action == NotificationDeliveryPolicy.EmailAction.RETURN_UNCERTAIN) {
            reconcileInvoice(job.tenantId(), delivery.invoiceId());
            return result(delivery, delivery.providerMessageId(), true, true);
        }
        if (action == NotificationDeliveryPolicy.EmailAction.MARK_UNCERTAIN) {
            String token = providerToken(delivery);
            markUncertain(delivery, token, "Recovered an interrupted SMTP attempt; delivery must be reconciled by Message-ID");
            reconcileInvoice(job.tenantId(), delivery.invoiceId());
            return result(delivery, token, true, true);
        }
        if (action == NotificationDeliveryPolicy.EmailAction.REJECT) {
            throw new DomainException("NOTIFICATION_NOT_SENDABLE", "Notification is not in a sendable state", 409,
                    Map.of("notification_id", delivery.id(), "status", delivery.status()));
        }

        PreparedEmail prepared;
        try {
            prepared = prepareEmail(delivery);
        } catch (Exception exception) {
            markFailed(delivery, job, exception);
            reconcileInvoice(job.tenantId(), delivery.invoiceId());
            throw exception;
        }

        beginAttempt(delivery, job.attemptCount(), prepared.providerToken(), false);
        try {
            mailSender.send(prepared.message());
        } catch (Exception exception) {
            markUncertain(delivery, prepared.providerToken(),
                    "SMTP returned an ambiguous outcome: " + safeMessage(exception));
            reconcileInvoice(job.tenantId(), delivery.invoiceId());
            return result(delivery, prepared.providerToken(), false, true);
        }

        try {
            markSent(delivery, prepared.providerToken());
        } catch (RuntimeException exception) {
            markUncertain(delivery, prepared.providerToken(),
                    "SMTP completed but SENT persistence failed: " + safeMessage(exception));
            reconcileInvoice(job.tenantId(), delivery.invoiceId());
            return result(delivery, prepared.providerToken(), false, true);
        }
        reconcileInvoice(job.tenantId(), delivery.invoiceId());
        return result(delivery, prepared.providerToken(), false, false);
    }

    private JsonNode handleWebhook(BackgroundJob job, Delivery delivery) throws Exception {
        if ("SENT".equals(delivery.status())) {
            reconcileInvoice(job.tenantId(), delivery.invoiceId());
            return result(delivery, delivery.providerMessageId(), true, false);
        }
        if ("CANCELLED".equals(delivery.status()) || "UNCERTAIN".equals(delivery.status())) {
            throw new DomainException("NOTIFICATION_NOT_SENDABLE", "Notification is not in a sendable state", 409,
                    Map.of("notification_id", delivery.id(), "status", delivery.status()));
        }
        String token = eventId(delivery);
        beginAttempt(delivery, job.attemptCount(), token, true);
        try {
            String providerMessageId = sendWebhook(delivery);
            markSent(delivery, providerMessageId);
            reconcileInvoice(job.tenantId(), delivery.invoiceId());
            return result(delivery, providerMessageId, false, false);
        } catch (Exception exception) {
            markFailed(delivery, job, exception);
            reconcileInvoice(job.tenantId(), delivery.invoiceId());
            throw exception;
        }
    }

    private PreparedEmail prepareEmail(Delivery delivery) throws Exception {
        JsonNode payload = delivery.payload();
        byte[] pdf = objectStorage.get(required(payload, "pdf_bucket"), required(payload, "pdf_object_key"));
        String expectedHash = required(payload, "pdf_sha256");
        String actualHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf));
        if (!MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.US_ASCII),
                actualHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new DomainException("INVOICE_PDF_HASH_MISMATCH",
                    "Invoice PDF hash does not match the frozen file record", 409,
                    Map.of("notification_id", delivery.id()));
        }

        String messageId = providerToken(delivery);
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(emailFrom);
        helper.setTo(delivery.recipient());
        helper.setSubject(delivery.subject());
        helper.setText(required(payload, "body"), false);
        helper.addAttachment(required(payload, "invoice_number") + ".pdf", new ByteArrayResource(pdf),
                "application/pdf");
        message.setHeader("Message-ID", messageId);
        message.setHeader("X-Auto-Invoice-Event-Id", eventId(delivery));
        return new PreparedEmail(message, messageId);
    }

    private String sendWebhook(Delivery delivery) throws Exception {
        UUID endpointId = UUID.fromString(required(delivery.payload(), "webhook_endpoint_id"));
        WebhookEndpoint endpoint = loadEndpoint(delivery.tenantId(), endpointId);
        String targetUrl = urlPolicy.validate(endpoint.targetUrl()).toString();
        String secret = secretCipher.decrypt(endpoint.secretCiphertext(), delivery.tenantId(),
                "webhook-endpoint:" + endpoint.id());
        String body = objectMapper.writeValueAsString(delivery.payload());
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = "sha256=" + hmac(secret, timestamp + "." + body);
        Request request = new Request.Builder()
                .url(targetUrl)
                .post(RequestBody.create(body, JSON))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Auto-Invoice-Webhook/0.1")
                .header("X-Auto-Invoice-Event-Id", eventId(delivery))
                .header("X-Auto-Invoice-Timestamp", timestamp)
                .header("X-Auto-Invoice-Signature", signature)
                .build();
        try (Response response = webhookClient.newCall(request).execute()) {
            if (response.code() < 200 || response.code() >= 300) {
                throw new DomainException("WEBHOOK_RESPONSE_REJECTED",
                        "Webhook endpoint returned a non-success status", 503,
                        Map.of("status", response.code(), "endpoint_id", endpoint.id()));
            }
        }
        jdbc.sql("""
                        UPDATE webhook_endpoints
                        SET status = 'ACTIVE', last_success_at = now(), consecutive_failures = 0, updated_at = now()
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", delivery.tenantId()).param("id", endpoint.id()).update();
        return eventId(delivery);
    }

    private Delivery load(UUID tenantId, UUID notificationId) {
        return jdbc.sql("""
                        SELECT id, tenant_id, invoice_id, channel, recipient, subject_rendered,
                               payload_json, status, provider_message_id
                        FROM notification_logs
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId).param("id", notificationId).query(this::mapDelivery).optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Notification delivery was not found", 404,
                        Map.of("notification_id", notificationId)));
    }

    private Delivery mapDelivery(ResultSet rs, int row) throws SQLException {
        try {
            return new Delivery(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                    rs.getObject("invoice_id", UUID.class), rs.getString("channel"), rs.getString("recipient"),
                    rs.getString("subject_rendered"), objectMapper.readTree(rs.getString("payload_json")),
                    rs.getString("status"), rs.getString("provider_message_id"));
        } catch (JsonProcessingException exception) {
            throw new SQLException("Persisted notification payload is invalid", exception);
        }
    }

    private WebhookEndpoint loadEndpoint(UUID tenantId, UUID endpointId) {
        return jdbc.sql("""
                        SELECT id, target_url, signing_secret_ciphertext, status
                        FROM webhook_endpoints WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId).param("id", endpointId)
                .query((rs, row) -> new WebhookEndpoint(rs.getObject("id", UUID.class), rs.getString("target_url"),
                        rs.getString("signing_secret_ciphertext"), rs.getString("status")))
                .optional().filter(endpoint -> !"DISABLED".equals(endpoint.status()))
                .orElseThrow(() -> new DomainException("WEBHOOK_ENDPOINT_UNAVAILABLE",
                        "Webhook endpoint is missing or disabled", 422, Map.of("webhook_endpoint_id", endpointId)));
    }

    private void beginAttempt(Delivery delivery, int attemptCount, String providerToken,
                              boolean recoverInterruptedAttempt) {
        int updated = jdbc.sql("""
                        UPDATE notification_logs
                        SET status = 'SENDING', attempt_count = :attemptCount,
                            provider_message_id = COALESCE(provider_message_id, :providerToken),
                            send_started_at = COALESCE(send_started_at, now()),
                            last_error_code = NULL, last_error_message = NULL, updated_at = now()
                        WHERE tenant_id = :tenantId AND id = :id
                          AND (status IN ('PENDING', 'RETRY', 'FAILED', 'DEAD')
                               OR (:recoverInterruptedAttempt AND status = 'SENDING'))
                        """)
                .param("attemptCount", attemptCount).param("providerToken", providerToken)
                .param("tenantId", delivery.tenantId()).param("id", delivery.id())
                .param("recoverInterruptedAttempt", recoverInterruptedAttempt).update();
        if (updated != 1) {
            throw new DomainException("NOTIFICATION_NOT_SENDABLE", "Notification is not in a sendable state", 409,
                    Map.of("notification_id", delivery.id(), "status", delivery.status()));
        }
    }

    private void markSent(Delivery delivery, String providerMessageId) {
        int updated = jdbc.sql("""
                        UPDATE notification_logs
                        SET status = 'SENT', provider_message_id = :providerMessageId,
                            sent_at = COALESCE(sent_at, now()), next_attempt_at = NULL,
                            last_error_code = NULL, last_error_message = NULL, updated_at = now()
                        WHERE tenant_id = :tenantId AND id = :id AND status = 'SENDING'
                        """)
                .param("providerMessageId", providerMessageId).param("tenantId", delivery.tenantId())
                .param("id", delivery.id()).update();
        if (updated != 1) {
            throw new DomainException("NOTIFICATION_SEND_STATE_CHANGED",
                    "Notification state changed before the delivery result was persisted", 409,
                    Map.of("notification_id", delivery.id()));
        }
    }

    private void markUncertain(Delivery delivery, String providerMessageId, String detail) {
        int updated = jdbc.sql("""
                        UPDATE notification_logs
                        SET status = 'UNCERTAIN', provider_message_id = :providerMessageId,
                            next_attempt_at = NULL, last_error_code = 'DELIVERY_OUTCOME_UNKNOWN',
                            last_error_message = :detail, updated_at = now()
                        WHERE tenant_id = :tenantId AND id = :id AND status = 'SENDING'
                        """)
                .param("providerMessageId", providerMessageId).param("detail", truncate(detail, 4000))
                .param("tenantId", delivery.tenantId()).param("id", delivery.id()).update();
        if (updated == 0) {
            String status = jdbc.sql("SELECT status FROM notification_logs WHERE tenant_id = :tenantId AND id = :id")
                    .param("tenantId", delivery.tenantId()).param("id", delivery.id())
                    .query(String.class).optional().orElse("MISSING");
            if (!"SENT".equals(status) && !"UNCERTAIN".equals(status)) {
                throw new DomainException("NOTIFICATION_SEND_STATE_CHANGED",
                        "Notification state changed while recording an uncertain delivery", 409,
                        Map.of("notification_id", delivery.id(), "status", status));
            }
        }
    }

    private void markFailed(Delivery delivery, BackgroundJob job, Exception exception) {
        boolean dead = job.attemptCount() >= job.maxAttempts();
        String errorCode = exception instanceof DomainException domain
                ? domain.code() : exception.getClass().getSimpleName().toUpperCase();
        String errorMessage = exception.getMessage() == null ? "Notification delivery failed" : exception.getMessage();
        long delaySeconds = Math.min(300, 5L << Math.min(job.attemptCount(), 6));
        jdbc.sql("""
                        UPDATE notification_logs
                        SET status = :status, attempt_count = :attemptCount,
                            last_error_code = :errorCode, last_error_message = :errorMessage,
                            next_attempt_at = CASE WHEN :dead THEN NULL ELSE now() + (:delay * interval '1 second') END,
                            updated_at = now()
                        WHERE tenant_id = :tenantId AND id = :id
                          AND status NOT IN ('SENT', 'CANCELLED', 'UNCERTAIN')
                        """)
                .param("status", dead ? "DEAD" : "RETRY").param("attemptCount", job.attemptCount())
                .param("errorCode", truncate(errorCode, 100)).param("errorMessage", truncate(errorMessage, 4000))
                .param("dead", dead).param("delay", delaySeconds)
                .param("tenantId", delivery.tenantId()).param("id", delivery.id()).update();
        if ("WEBHOOK".equals(delivery.channel())) {
            String endpointValue = delivery.payload().path("webhook_endpoint_id").asText(null);
            if (endpointValue != null) {
                jdbc.sql("""
                                UPDATE webhook_endpoints
                                SET last_failure_at = now(), consecutive_failures = consecutive_failures + 1,
                                    status = CASE WHEN consecutive_failures + 1 >= 10 THEN 'ERROR' ELSE status END,
                                    updated_at = now()
                                WHERE tenant_id = :tenantId AND id = :id
                                """)
                        .param("tenantId", delivery.tenantId()).param("id", UUID.fromString(endpointValue)).update();
            }
        }
    }

    private void reconcileInvoice(UUID tenantId, UUID invoiceId) {
        if (invoiceId == null) {
            return;
        }
        jdbc.sql("""
                        WITH delivery AS (
                            SELECT COUNT(*) FILTER (WHERE status = 'SENT') AS sent_count,
                                   COUNT(*) FILTER (WHERE status IN ('PENDING', 'SENDING', 'RETRY')) AS pending_count,
                                   COUNT(*) FILTER (WHERE status IN ('FAILED', 'DEAD', 'UNCERTAIN')) AS failed_count
                            FROM notification_logs
                            WHERE tenant_id = :tenantId AND invoice_id = :invoiceId
                        )
                        UPDATE invoices invoice
                        SET send_status = CASE
                                WHEN delivery.pending_count > 0 AND delivery.sent_count = 0 THEN 'QUEUED'
                                WHEN delivery.pending_count > 0 AND delivery.sent_count > 0 THEN 'PARTIALLY_SENT'
                                WHEN delivery.sent_count > 0 AND delivery.failed_count = 0 THEN 'SENT'
                                WHEN delivery.sent_count > 0 THEN 'PARTIALLY_SENT'
                                ELSE 'FAILED'
                            END,
                            document_status = CASE WHEN delivery.sent_count > 0 AND invoice.document_status = 'CONFIRMED'
                                THEN 'SENT' ELSE invoice.document_status END,
                            sent_at = CASE WHEN delivery.sent_count > 0 THEN COALESCE(invoice.sent_at, clock_timestamp())
                                ELSE invoice.sent_at END,
                            updated_at = clock_timestamp(), version = version + 1
                        FROM delivery
                        WHERE invoice.tenant_id = :tenantId AND invoice.id = :invoiceId
                        """)
                .param("tenantId", tenantId).param("invoiceId", invoiceId).update();
    }

    private ObjectNode result(Delivery delivery, String providerMessageId, boolean recovered, boolean uncertain) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("notification_id", delivery.id().toString());
        if (delivery.invoiceId() != null) {
            result.put("invoice_id", delivery.invoiceId().toString());
        }
        result.put("channel", delivery.channel());
        result.put("provider_message_id", providerMessageId);
        result.put("recovered", recovered);
        result.put("uncertain", uncertain);
        return result;
    }

    private String providerToken(Delivery delivery) {
        return delivery.providerMessageId() == null || delivery.providerMessageId().isBlank()
                ? "<" + delivery.id() + "@auto-invoice.local>"
                : delivery.providerMessageId();
    }

    private String eventId(Delivery delivery) {
        return delivery.payload().path("event_id").asText(delivery.id().toString());
    }

    private String required(JsonNode payload, String field) {
        String value = payload.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new DomainException("NOTIFICATION_PAYLOAD_INVALID", "Notification payload is missing a required field",
                    422, Map.of("field", field));
        }
        return value;
    }

    private UUID parseNotificationId(JsonNode payload) {
        try {
            return UUID.fromString(payload.path("notification_id").asText());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("SEND_NOTIFICATION payload requires notification_id", exception);
        }
    }

    private String hmac(String secret, String material) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private String truncate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private record PreparedEmail(MimeMessage message, String providerToken) {
    }

    private record Delivery(UUID id, UUID tenantId, UUID invoiceId, String channel, String recipient,
                            String subject, JsonNode payload, String status, String providerMessageId) {
    }

    private record WebhookEndpoint(UUID id, String targetUrl, String secretCiphertext, String status) {
    }
}
