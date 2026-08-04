package com.autoinvoice.platform.audit;

import com.autoinvoice.platform.UuidV7;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {
    private static final String HASH_FORMAT = "auto-invoice-audit-v2";
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public AuditService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(UUID tenantId, String actorType, UUID actorId, String actorDisplay,
                       String action, String objectType, UUID objectId, Object before, Object after,
                       String reason, String requestId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("reason", reason == null ? "" : reason);
        record(tenantId, actorType, actorId, actorDisplay, action, objectType, objectId, before, after,
                new AuditRequestContext(requestId, null, null, null, metadata));
    }

    @Transactional
    public void record(UUID tenantId, String actorType, UUID actorId, String actorDisplay,
                       String action, String objectType, UUID objectId, Object before, Object after,
                       AuditRequestContext requestContext) {
        AuditRequestContext context = requestContext == null ? AuditRequestContext.empty() : requestContext;
        try {
            String beforeJson = canonicalJson(before);
            String afterJson = canonicalJson(after);
            String metadataJson = canonicalJson(context.metadata());
            Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
            UUID auditId = UuidV7.generate();

            jdbc.sql("""
                            INSERT INTO audit_chain_heads(tenant_id, last_event_hash, last_event_id)
                            VALUES (:tenantId, NULL, NULL)
                            ON CONFLICT (tenant_id) DO NOTHING
                            """)
                    .param("tenantId", tenantId)
                    .update();
            String previousHash = jdbc.sql("""
                            SELECT last_event_hash
                            FROM audit_chain_heads
                            WHERE tenant_id = :tenantId
                            FOR UPDATE
                            """)
                    .param("tenantId", tenantId)
                    .query(String.class)
                    .optional()
                    .orElse(null);

            HashMaterial material = new HashMaterial(previousHash, auditId, tenantId, actorType, actorId,
                    actorDisplay, action, objectType, objectId, context.correlationId(), context.requestId(),
                    beforeJson, afterJson, metadataJson, context.ipAddress(), context.userAgent(), createdAt);
            String eventHash = computeEventHash(material);

            jdbc.sql("""
                            INSERT INTO audit_logs(
                                id, tenant_id, actor_type, actor_id, actor_display, action, object_type,
                                object_id, correlation_id, request_id, before_json, after_json, metadata_json,
                                ip_address, user_agent, previous_hash, event_hash, created_at
                            ) VALUES (
                                :id, :tenantId, :actorType, :actorId, :actorDisplay, :action, :objectType,
                                :objectId, :correlationId, :requestId, CAST(:beforeJson AS jsonb),
                                CAST(:afterJson AS jsonb), CAST(:metadataJson AS jsonb), CAST(:ipAddress AS inet),
                                :userAgent, :previousHash, :eventHash, :createdAt
                            )
                            """)
                    .param("id", auditId)
                    .param("tenantId", tenantId)
                    .param("actorType", actorType)
                    .param("actorId", actorId)
                    .param("actorDisplay", actorDisplay)
                    .param("action", action)
                    .param("objectType", objectType)
                    .param("objectId", objectId)
                    .param("correlationId", context.correlationId())
                    .param("requestId", context.requestId())
                    .param("beforeJson", beforeJson)
                    .param("afterJson", afterJson)
                    .param("metadataJson", metadataJson)
                    .param("ipAddress", context.ipAddress())
                    .param("userAgent", context.userAgent())
                    .param("previousHash", previousHash)
                    .param("eventHash", eventHash)
                    .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                    .update();

            int advanced = jdbc.sql("""
                            UPDATE audit_chain_heads
                            SET last_event_hash = :eventHash, last_event_id = :eventId, updated_at = now()
                            WHERE tenant_id = :tenantId
                              AND last_event_hash IS NOT DISTINCT FROM CAST(:previousHash AS char(64))
                            """)
                    .param("eventHash", eventHash)
                    .param("eventId", auditId)
                    .param("tenantId", tenantId)
                    .param("previousHash", previousHash)
                    .update();
            if (advanced != 1) {
                throw new IllegalStateException("Audit chain head changed while it was locked");
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Audit payload is not serializable", exception);
        }
    }

    String canonicalJson(Object value) throws JsonProcessingException {
        if (value == null) {
            return null;
        }
        return objectMapper.writeValueAsString(canonicalize(objectMapper.valueToTree(value)));
    }

    String canonicalizeJsonText(String value) throws JsonProcessingException {
        if (value == null) {
            return null;
        }
        return objectMapper.writeValueAsString(canonicalize(objectMapper.readTree(value)));
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value == null || value.isNull() || value.isValueNode()) {
            return value;
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        ObjectNode result = objectMapper.createObjectNode();
        value.properties().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.set(entry.getKey(), canonicalize(entry.getValue())));
        return result;
    }

    static String computeEventHash(HashMaterial material) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeField(output, HASH_FORMAT);
                writeField(output, material.previousHash());
                writeField(output, material.auditId());
                writeField(output, material.tenantId());
                writeField(output, material.actorType());
                writeField(output, material.actorId());
                writeField(output, material.actorDisplay());
                writeField(output, material.action());
                writeField(output, material.objectType());
                writeField(output, material.objectId());
                writeField(output, material.correlationId());
                writeField(output, material.requestId());
                writeField(output, material.beforeJson());
                writeField(output, material.afterJson());
                writeField(output, material.metadataJson());
                writeField(output, material.ipAddress());
                writeField(output, material.userAgent());
                writeField(output, material.createdAt());
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode audit hash material", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void writeField(DataOutputStream output, Object value) throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        byte[] encoded = value.toString().getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    public record AuditRequestContext(String requestId, String correlationId, String ipAddress,
                                      String userAgent, Map<String, Object> metadata) {
        public AuditRequestContext {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        private static AuditRequestContext empty() {
            return new AuditRequestContext(null, null, null, null, Map.of());
        }
    }

    record HashMaterial(String previousHash, UUID auditId, UUID tenantId, String actorType, UUID actorId,
                        String actorDisplay, String action, String objectType, UUID objectId,
                        String correlationId, String requestId, String beforeJson, String afterJson,
                        String metadataJson, String ipAddress, String userAgent, Instant createdAt) {
    }
}
