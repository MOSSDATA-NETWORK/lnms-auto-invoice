package com.autoinvoice.api.idempotency;

import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.security.SecretCipher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class IdempotencyExecutor {
    public static final String HEADER = "Idempotency-Key";
    private static final Set<String> REPLAY_HEADERS = Set.of(HttpHeaders.ETAG, HttpHeaders.LOCATION, HttpHeaders.CONTENT_TYPE);
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final SecretCipher secretCipher;
    private final byte[] fingerprintKey;

    @Autowired
    public IdempotencyExecutor(JdbcClient jdbc, ObjectMapper objectMapper, SecretCipher secretCipher,
                               @Value("${auto-invoice.security.master-key-base64:}") String masterKeyBase64) {
        this(jdbc, objectMapper, secretCipher, deriveFingerprintKey(masterKeyBase64));
    }

    IdempotencyExecutor(JdbcClient jdbc, ObjectMapper objectMapper, SecretCipher secretCipher,
                        byte[] fingerprintKey) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.secretCipher = secretCipher;
        this.fingerprintKey = fingerprintKey.clone();
    }

    @Transactional
    public <T> ResponseEntity<T> execute(UUID tenantId, String key, String method, String path,
                                         Object requestBody, Class<T> responseType,
                                         Supplier<ResponseEntity<T>> command) {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null
                ? null : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof AuthenticatedUser actor) || !actor.tenantId().equals(tenantId)) {
            throw new DomainException("AUTHENTICATION_REQUIRED",
                    "An authenticated actor is required for an idempotent command", 401, Map.of());
        }
        return execute(tenantId, actor.userId(), key, method, path, requestBody, responseType, command);
    }

    @Transactional
    public <T> ResponseEntity<T> execute(UUID tenantId, UUID actorId, String key, String method, String path,
                                         Object requestBody, Class<T> responseType,
                                         Supplier<ResponseEntity<T>> command) {
        validateKey(key);
        if (actorId == null) {
            throw new IllegalArgumentException("The idempotency actor is required");
        }
        String requestHash = fingerprint(requestBody);
        UUID ownerToken = UUID.randomUUID();
        UUID recordId = UuidV7.generate();
        jdbc.sql("""
                        DELETE FROM idempotency_keys
                        WHERE tenant_id = :tenantId AND actor_id = :actorId
                          AND idempotency_key = :key AND expires_at <= clock_timestamp()
                        """)
                .param("tenantId", tenantId)
                .param("actorId", actorId)
                .param("key", key)
                .update();
        int inserted = jdbc.sql("""
                        INSERT INTO idempotency_keys(
                            id, tenant_id, actor_id, idempotency_key, http_method, request_path, request_hash,
                            state, owner_token, locked_until, expires_at
                        ) VALUES (
                            :id, :tenantId, :actorId, :key, :method, :path, :requestHash,
                            'PROCESSING', :ownerToken, now() + interval '2 minutes', now() + interval '24 hours'
                        )
                        ON CONFLICT (tenant_id, actor_id, idempotency_key) DO NOTHING
                        """)
                .param("id", recordId)
                .param("tenantId", tenantId)
                .param("actorId", actorId)
                .param("key", key)
                .param("method", method)
                .param("path", path)
                .param("requestHash", requestHash)
                .param("ownerToken", ownerToken)
                .update();

        if (inserted == 0) {
            StoredResponse stored = load(tenantId, actorId, key);
            assertSameRequest(stored, method, path, requestHash);
            if ("COMPLETED".equals(stored.state())) {
                return replay(stored, responseType, tenantId, actorId, key);
            }
            int claimed = jdbc.sql("""
                            UPDATE idempotency_keys
                            SET state = 'PROCESSING', owner_token = :ownerToken,
                                locked_until = now() + interval '2 minutes', updated_at = now()
                            WHERE tenant_id = :tenantId AND actor_id = :actorId AND idempotency_key = :key
                              AND (state = 'FAILED' OR locked_until IS NULL OR locked_until < clock_timestamp())
                            """)
                    .param("ownerToken", ownerToken)
                    .param("tenantId", tenantId)
                    .param("actorId", actorId)
                    .param("key", key)
                    .update();
            if (claimed != 1) {
                throw new DomainException("IDEMPOTENCY_IN_PROGRESS",
                        "A request with this idempotency key is still processing", 409,
                        Map.of("idempotency_key", key));
            }
        }

        ResponseEntity<T> response = command.get();
        String bodyJson = serializeNullable(response.getBody(), tenantId, actorId, key, path);
        String headersJson = serializeHeaders(response.getHeaders());
        int completed = jdbc.sql("""
                        UPDATE idempotency_keys
                        SET state = 'COMPLETED', response_status = :status,
                            response_headers_json = CAST(:headers AS jsonb),
                            response_body_json = CAST(:body AS jsonb),
                            locked_until = NULL, updated_at = now()
                        WHERE tenant_id = :tenantId AND actor_id = :actorId AND idempotency_key = :key
                          AND state = 'PROCESSING' AND owner_token = :ownerToken
                        """)
                .param("status", response.getStatusCode().value())
                .param("headers", headersJson)
                .param("body", bodyJson)
                .param("tenantId", tenantId)
                .param("actorId", actorId)
                .param("key", key)
                .param("ownerToken", ownerToken)
                .update();
        if (completed != 1) {
            throw new DomainException("IDEMPOTENCY_LEASE_LOST", "The idempotent command lease was lost", 409,
                    Map.of("idempotency_key", key));
        }
        return response;
    }

    String fingerprint(Object value) {
        try {
            JsonNode canonical = canonicalize(objectMapper.valueToTree(value));
            byte[] bytes = objectMapper.writeValueAsBytes(canonical);
            if (fingerprintKey.length != 32) {
                throw new IllegalStateException(
                        "AUTO_INVOICE_MASTER_KEY_BASE64 must decode to exactly 32 bytes");
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(fingerprintKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(bytes));
        } catch (JsonProcessingException | GeneralSecurityException exception) {
            throw new IllegalArgumentException("Unable to fingerprint idempotent request", exception);
        }
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

    private StoredResponse load(UUID tenantId, UUID actorId, String key) {
        return jdbc.sql("""
                        SELECT http_method, request_path, request_hash, state, response_status,
                               response_headers_json, response_body_json
                        FROM idempotency_keys
                        WHERE tenant_id = :tenantId AND actor_id = :actorId AND idempotency_key = :key
                        """)
                .param("tenantId", tenantId)
                .param("actorId", actorId)
                .param("key", key)
                .query(this::map)
                .single();
    }

    private void assertSameRequest(StoredResponse stored, String method, String path, String hash) {
        if (!stored.method().equals(method) || !stored.path().equals(path) || !stored.requestHash().equals(hash)) {
            throw new DomainException("IDEMPOTENCY_KEY_REUSED",
                    "The idempotency key was already used for a different request", 409, Map.of());
        }
    }

    private <T> ResponseEntity<T> replay(StoredResponse stored, Class<T> responseType,
                                         UUID tenantId, UUID actorId, String key) {
        try {
            JsonNode replayBody = decryptSensitiveBody(stored.body(), tenantId, actorId, key, stored.path());
            T body = replayBody == null || replayBody.isNull() || responseType == Void.class
                    ? null : objectMapper.treeToValue(replayBody, responseType);
            HttpHeaders headers = new HttpHeaders();
            stored.headers().forEach(headers::put);
            return new ResponseEntity<>(body, headers, HttpStatusCode.valueOf(stored.status()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted idempotency response is invalid", exception);
        }
    }

    private String serializeNullable(Object body, UUID tenantId, UUID actorId, String key, String path) {
        try {
            if (body == null) {
                return "null";
            }
            String serialized = objectMapper.writeValueAsString(body);
            if (!isSensitivePath(path)) {
                return serialized;
            }
            ObjectNode encrypted = objectMapper.createObjectNode();
            encrypted.put("_encrypted", secretCipher.encrypt(serialized, tenantId,
                    sensitivePurpose(actorId, key, path)));
            return objectMapper.writeValueAsString(encrypted);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Command response is not serializable", exception);
        }
    }

    private JsonNode decryptSensitiveBody(JsonNode body, UUID tenantId, UUID actorId, String key, String path)
            throws JsonProcessingException {
        if (body == null || !body.isObject() || !body.hasNonNull("_encrypted")) {
            return body;
        }
        String decrypted = secretCipher.decrypt(body.path("_encrypted").asText(), tenantId,
                sensitivePurpose(actorId, key, path));
        return objectMapper.readTree(decrypted);
    }

    private boolean isSensitivePath(String path) {
        return path.equals("/api/v1/auth/mfa/enrollment")
                || path.equals("/api/v1/auth/mfa/confirm")
                || path.equals("/api/v1/auth/mfa/recovery-codes");
    }

    private String sensitivePurpose(UUID actorId, String key, String path) {
        return "idempotency-response:" + actorId + ":" + path + ":" + key;
    }

    private String serializeHeaders(HttpHeaders headers) {
        Map<String, List<String>> selected = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (REPLAY_HEADERS.stream().anyMatch(name::equalsIgnoreCase)) {
                selected.put(name, List.copyOf(values));
            }
        });
        try {
            return objectMapper.writeValueAsString(selected);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to persist response headers", exception);
        }
    }

    private StoredResponse map(ResultSet rs, int rowNum) throws SQLException {
        try {
            String headers = rs.getString("response_headers_json");
            String body = rs.getString("response_body_json");
            return new StoredResponse(
                    rs.getString("http_method"), rs.getString("request_path"), rs.getString("request_hash"),
                    rs.getString("state"), rs.getObject("response_status", Integer.class),
                    headers == null ? Map.of() : objectMapper.readValue(headers, new TypeReference<>() {}),
                    body == null ? null : objectMapper.readTree(body));
        } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid persisted idempotency JSON", exception);
        }
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 300) {
            throw new DomainException("IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key must contain between 1 and 300 characters", 400, Map.of());
        }
    }

    private static byte[] deriveFingerprintKey(String encodedMasterKey) {
        if (encodedMasterKey == null || encodedMasterKey.isBlank()) {
            return new byte[0];
        }
        final byte[] masterKey;
        try {
            masterKey = Base64.getDecoder().decode(encodedMasterKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("AUTO_INVOICE_MASTER_KEY_BASE64 is not valid Base64", exception);
        }
        if (masterKey.length != 32) {
            throw new IllegalStateException("AUTO_INVOICE_MASTER_KEY_BASE64 must decode to exactly 32 bytes");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterKey, "HmacSHA256"));
            return mac.doFinal("auto-invoice/idempotency-fingerprint/v1".getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to derive the idempotency fingerprint key", exception);
        }
    }

    private record StoredResponse(String method, String path, String requestHash, String state,
                                  Integer status, Map<String, List<String>> headers, JsonNode body) {
    }
}
