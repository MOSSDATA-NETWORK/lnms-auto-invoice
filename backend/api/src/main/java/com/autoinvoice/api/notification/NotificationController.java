package com.autoinvoice.api.notification;

import com.autoinvoice.api.http.VersionEtag;
import com.autoinvoice.api.idempotency.IdempotencyExecutor;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.notification.NotificationService;
import com.autoinvoice.notification.WebhookUrlPolicy;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.audit.AuditService;
import com.autoinvoice.platform.security.SecretCipher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class NotificationController {
    private static final Set<String> SUPPORTED_EVENTS = Set.of("invoice.confirmed");
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final SecretCipher secretCipher;
    private final WebhookUrlPolicy urlPolicy;
    private final NotificationService notificationService;
    private final IdempotencyExecutor idempotency;
    private final AuditService audit;

    public NotificationController(JdbcClient jdbc, ObjectMapper objectMapper, SecretCipher secretCipher,
                                  WebhookUrlPolicy urlPolicy, NotificationService notificationService,
                                  IdempotencyExecutor idempotency, AuditService audit) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.secretCipher = secretCipher;
        this.urlPolicy = urlPolicy;
        this.notificationService = notificationService;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @GetMapping("/webhook-endpoints")
    @PreAuthorize("hasAnyAuthority('invoice.send','system.admin')")
    public List<WebhookEndpointResponse> listEndpoints(Authentication authentication) {
        return jdbc.sql("""
                        SELECT * FROM webhook_endpoints
                        WHERE tenant_id = :tenantId ORDER BY endpoint_code
                        """)
                .param("tenantId", principal(authentication).tenantId()).query(this::mapEndpoint).list();
    }

    @GetMapping("/webhook-endpoints/{endpointId}")
    @PreAuthorize("hasAnyAuthority('invoice.send','system.admin')")
    public ResponseEntity<WebhookEndpointResponse> getEndpoint(Authentication authentication,
                                                               @PathVariable UUID endpointId) {
        WebhookEndpointResponse endpoint = findEndpoint(principal(authentication).tenantId(), endpointId);
        return ResponseEntity.ok().eTag(VersionEtag.format(endpoint.version())).body(endpoint);
    }

    @PostMapping("/webhook-endpoints")
    @PreAuthorize("hasAuthority('system.admin')")
    public ResponseEntity<WebhookEndpointResponse> createEndpoint(
            Authentication authentication, @RequestHeader(IdempotencyExecutor.HEADER) String key,
            @Valid @RequestBody WebhookEndpointCreateRequest request, HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST",
                "/api/v1/webhook-endpoints", request,
                WebhookEndpointResponse.class, () -> {
                    validateEvents(request.eventTypes());
                    URI target = urlPolicy.validate(request.targetUrl());
                    UUID id = UuidV7.generate();
                    String ciphertext = secretCipher.encrypt(request.signingSecret(), actor.tenantId(), secretPurpose(id));
                    jdbc.sql("""
                                    INSERT INTO webhook_endpoints(
                                        id, tenant_id, endpoint_code, endpoint_name, target_url,
                                        signing_secret_ciphertext, event_types_json, status
                                    ) VALUES (
                                        :id, :tenantId, :code, :name, :targetUrl,
                                        :secret, CAST(:events AS jsonb), 'ACTIVE'
                                    )
                                    """)
                            .param("id", id).param("tenantId", actor.tenantId()).param("code", request.endpointCode())
                            .param("name", request.endpointName()).param("targetUrl", target.toString())
                            .param("secret", ciphertext).param("events", json(request.eventTypes())).update();
                    WebhookEndpointResponse created = findEndpoint(actor.tenantId(), id);
                    record(actor, "webhook_endpoint.created", id, null, created, request.reason(), servletRequest);
                    return ResponseEntity.status(HttpStatus.CREATED).eTag(VersionEtag.format(0)).body(created);
                });
    }

    @PostMapping("/webhook-endpoints/{endpointId}")
    @PreAuthorize("hasAuthority('system.admin')")
    public ResponseEntity<WebhookEndpointResponse> updateEndpoint(
            Authentication authentication, @PathVariable UUID endpointId,
            @RequestHeader(IdempotencyExecutor.HEADER) String key,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody WebhookEndpointUpdateRequest request, HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        assertVersion(ifMatch, request.expectedVersion());
        String path = "/api/v1/webhook-endpoints/" + endpointId;
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                WebhookEndpointResponse.class, () -> {
                    validateEvents(request.eventTypes());
                    WebhookEndpointResponse before = findEndpoint(actor.tenantId(), endpointId);
                    URI target = urlPolicy.validate(request.targetUrl());
                    String ciphertext = request.signingSecret() == null || request.signingSecret().isBlank()
                            ? null : secretCipher.encrypt(request.signingSecret(), actor.tenantId(), secretPurpose(endpointId));
                    int updated = jdbc.sql("""
                                    UPDATE webhook_endpoints
                                    SET endpoint_name = :name, target_url = :targetUrl,
                                        signing_secret_ciphertext = COALESCE(:secret, signing_secret_ciphertext),
                                        event_types_json = CAST(:events AS jsonb), status = :status,
                                        consecutive_failures = CASE WHEN :status = 'ACTIVE' THEN 0 ELSE consecutive_failures END,
                                        updated_at = now(), version = version + 1
                                    WHERE tenant_id = :tenantId AND id = :id AND version = :version
                                    """)
                            .param("name", request.endpointName()).param("targetUrl", target.toString())
                            .param("secret", ciphertext).param("events", json(request.eventTypes()))
                            .param("status", request.status()).param("tenantId", actor.tenantId())
                            .param("id", endpointId).param("version", request.expectedVersion()).update();
                    if (updated != 1) {
                        throw versionConflict(request.expectedVersion());
                    }
                    WebhookEndpointResponse after = findEndpoint(actor.tenantId(), endpointId);
                    record(actor, "webhook_endpoint.updated", endpointId, before, after,
                            request.reason(), servletRequest);
                    return ResponseEntity.ok().eTag(VersionEtag.format(after.version())).body(after);
                });
    }

    @PostMapping("/invoices/{invoiceId}/send")
    @PreAuthorize("hasAuthority('invoice.send')")
    public ResponseEntity<NotificationService.QueueResult> sendInvoice(
            Authentication authentication, @PathVariable UUID invoiceId,
            @RequestHeader(IdempotencyExecutor.HEADER) String key,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody InvoiceSendRequest request, HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        assertVersion(ifMatch, request.expectedVersion());
        String path = "/api/v1/invoices/" + invoiceId + "/send";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                NotificationService.QueueResult.class, () -> {
                    NotificationService.QueueResult result = notificationService.queueInvoice(
                            actor.tenantId(), invoiceId, request.emails(), request.webhookEndpointIds(),
                            request.expectedVersion());
                    record(actor, "invoice.notification_queued", invoiceId, null, result,
                            request.reason(), servletRequest);
                    return ResponseEntity.accepted().eTag(VersionEtag.format(result.version())).body(result);
                });
    }

    @GetMapping("/notification-logs")
    @PreAuthorize("hasAnyAuthority('invoice.send','audit.read','system.admin')")
    public List<NotificationLogResponse> listLogs(Authentication authentication,
                                                   @RequestParam(name = "invoice_id", required = false) UUID invoiceId,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(defaultValue = "100") int limit) {
        return jdbc.sql("""
                        SELECT id, invoice_id, channel, event_type, recipient, status, attempt_count,
                               next_attempt_at, provider_message_id, last_error_code, last_error_message,
                               sent_at, created_at, updated_at
                        FROM notification_logs
                        WHERE tenant_id = :tenantId
                          AND (CAST(:invoiceId AS uuid) IS NULL OR invoice_id = :invoiceId)
                          AND (CAST(:status AS varchar) IS NULL OR status = :status)
                        ORDER BY created_at DESC LIMIT :limit
                        """)
                .param("tenantId", principal(authentication).tenantId()).param("invoiceId", invoiceId)
                .param("status", blankToNull(status)).param("limit", Math.max(1, Math.min(limit, 200)))
                .query(this::mapLog).list();
    }

    private WebhookEndpointResponse findEndpoint(UUID tenantId, UUID endpointId) {
        return jdbc.sql("SELECT * FROM webhook_endpoints WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", endpointId).query(this::mapEndpoint).optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Webhook endpoint was not found", 404,
                        Map.of("webhook_endpoint_id", endpointId)));
    }

    private WebhookEndpointResponse mapEndpoint(ResultSet rs, int row) throws SQLException {
        try {
            List<String> eventTypes = objectMapper.readValue(rs.getString("event_types_json"), new TypeReference<>() {});
            return new WebhookEndpointResponse(rs.getObject("id", UUID.class), rs.getString("endpoint_code"),
                    rs.getString("endpoint_name"), rs.getString("target_url"), eventTypes, rs.getString("status"),
                    rs.getObject("last_success_at", OffsetDateTime.class),
                    rs.getObject("last_failure_at", OffsetDateTime.class), rs.getInt("consecutive_failures"),
                    true, rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("updated_at", OffsetDateTime.class), rs.getLong("version"));
        } catch (JsonProcessingException exception) {
            throw new SQLException("Persisted webhook event types are invalid", exception);
        }
    }

    private NotificationLogResponse mapLog(ResultSet rs, int row) throws SQLException {
        return new NotificationLogResponse(rs.getObject("id", UUID.class), rs.getObject("invoice_id", UUID.class),
                rs.getString("channel"), rs.getString("event_type"), rs.getString("recipient"),
                rs.getString("status"), rs.getInt("attempt_count"),
                rs.getObject("next_attempt_at", OffsetDateTime.class), rs.getString("provider_message_id"),
                rs.getString("last_error_code"), rs.getString("last_error_message"),
                rs.getObject("sent_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private void validateEvents(List<String> events) {
        if (events == null || events.stream().anyMatch(event -> !SUPPORTED_EVENTS.contains(event))) {
            throw new DomainException("WEBHOOK_EVENT_UNSUPPORTED", "Webhook event subscription is unsupported", 422,
                    Map.of("supported_events", SUPPORTED_EVENTS));
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Webhook endpoint payload is not serializable", exception);
        }
    }

    private void assertVersion(String ifMatch, long bodyVersion) {
        long headerVersion = VersionEtag.parse(ifMatch);
        if (headerVersion != bodyVersion) {
            throw versionConflict(bodyVersion);
        }
    }

    private DomainException versionConflict(long expectedVersion) {
        return new DomainException("VERSION_CONFLICT", "Resource was modified by another request", 409,
                Map.of("expected_version", expectedVersion));
    }

    private String secretPurpose(UUID endpointId) {
        return "webhook-endpoint:" + endpointId;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private AuthenticatedUser principal(Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    private void record(AuthenticatedUser actor, String action, UUID id, Object before, Object after,
                        String reason, HttpServletRequest request) {
        audit.record(actor.tenantId(), "USER", actor.userId(), actor.displayName(), action,
                "notification", id, before, after, reason, request.getHeader("X-Request-Id"));
    }

    public record WebhookEndpointCreateRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{2,99}") String endpointCode,
            @NotBlank @Size(max = 240) String endpointName,
            @NotBlank @Size(max = 1200) String targetUrl,
            @NotBlank @Size(min = 16, max = 512) String signingSecret,
            @NotNull List<@NotBlank String> eventTypes,
            @NotBlank String reason) {
    }

    public record WebhookEndpointUpdateRequest(
            @PositiveOrZero long expectedVersion,
            @NotBlank @Size(max = 240) String endpointName,
            @NotBlank @Size(max = 1200) String targetUrl,
            @Size(min = 16, max = 512) String signingSecret,
            @NotNull List<@NotBlank String> eventTypes,
            @NotBlank @Pattern(regexp = "ACTIVE|DISABLED") String status,
            @NotBlank String reason) {
    }

    public record InvoiceSendRequest(@PositiveOrZero long expectedVersion,
                                     List<@NotBlank String> emails,
                                     List<UUID> webhookEndpointIds,
                                     @NotBlank String reason) {
    }

    public record WebhookEndpointResponse(UUID id, String endpointCode, String endpointName, String targetUrl,
                                          List<String> eventTypes, String status, OffsetDateTime lastSuccessAt,
                                          OffsetDateTime lastFailureAt, int consecutiveFailures,
                                          boolean signingSecretConfigured, OffsetDateTime createdAt,
                                          OffsetDateTime updatedAt, long version) {
    }

    public record NotificationLogResponse(UUID id, UUID invoiceId, String channel, String eventType,
                                          String recipient, String status, int attemptCount,
                                          OffsetDateTime nextAttemptAt, String providerMessageId,
                                          String lastErrorCode, String lastErrorMessage,
                                          OffsetDateTime sentAt, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }
}
