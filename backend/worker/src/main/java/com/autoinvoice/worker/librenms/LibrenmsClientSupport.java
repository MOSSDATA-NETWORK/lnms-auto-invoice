package com.autoinvoice.worker.librenms;

import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.security.SecretCipher;
import com.autoinvoice.usage.LibrenmsOriginPolicy;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Component
public class LibrenmsClientSupport {
    static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private final JdbcClient jdbc;
    private final SecretCipher secretCipher;
    private final LibrenmsOriginPolicy originPolicy;

    public LibrenmsClientSupport(JdbcClient jdbc, SecretCipher secretCipher, LibrenmsOriginPolicy originPolicy) {
        this.jdbc = jdbc;
        this.secretCipher = secretCipher;
        this.originPolicy = originPolicy;
    }

    public Connection connection(UUID tenantId, UUID instanceId) {
        Instance instance = jdbc.sql("""
                        SELECT id, base_url, api_token_ciphertext, timezone,
                               connect_timeout_ms, read_timeout_ms, status
                        FROM librenms_instances WHERE tenant_id = :tenantId AND id = :instanceId
                        """)
                .param("tenantId", tenantId).param("instanceId", instanceId).query(this::map).optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "LibreNMS instance was not found", 404,
                        Map.of("instance_id", instanceId)));
        if ("DISABLED".equals(instance.status())) {
            throw new DomainException("LIBRENMS_INSTANCE_DISABLED", "LibreNMS instance is disabled", 409,
                    Map.of("instance_id", instanceId));
        }
        String baseUrl = allowedBaseUrl(instance.baseUrl());
        String token = secretCipher.decrypt(instance.tokenCiphertext(), tenantId, "librenms-instance:" + instanceId);
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(instance.connectTimeoutMs()))
                        .followRedirects(HttpClient.Redirect.NEVER).build());
        requestFactory.setReadTimeout(Duration.ofMillis(instance.readTimeoutMs()));
        RestClient client = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory)
                .defaultHeader("X-Auth-Token", token).build();
        return new Connection(instance, client);
    }

    String allowedBaseUrl(String value) {
        return originPolicy.requireAllowed(value).toString();
    }

    private Instance map(ResultSet rs, int row) throws SQLException {
        return new Instance(rs.getObject("id", UUID.class), rs.getString("base_url"),
                rs.getString("api_token_ciphertext"), rs.getString("timezone"),
                rs.getInt("connect_timeout_ms"), rs.getInt("read_timeout_ms"), rs.getString("status"));
    }

    public record Connection(Instance instance, RestClient client) {
        public String get(String path) {
            String body = client.get().uri(path).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new DomainException("LIBRENMS_HTTP_ERROR",
                            "LibreNMS returned a non-success status", 502,
                            Map.of("upstream_status", response.getStatusCode().value()));
                }
                byte[] bytes = readLimited(response.getBody(), response.getHeaders().getContentLength(),
                        MAX_RESPONSE_BYTES);
                return new String(bytes, StandardCharsets.UTF_8);
            });
            if (body == null || body.isBlank()) {
                throw new IllegalArgumentException("LibreNMS returned an empty response");
            }
            return body;
        }
    }

    static byte[] readLimited(InputStream input, long contentLength, int maximumBytes) throws IOException {
        if (contentLength > maximumBytes) {
            throw responseTooLarge(maximumBytes);
        }
        byte[] bytes = input.readNBytes(maximumBytes + 1);
        if (bytes.length > maximumBytes) {
            throw responseTooLarge(maximumBytes);
        }
        return bytes;
    }

    private static DomainException responseTooLarge(int maximumBytes) {
        return new DomainException("LIBRENMS_RESPONSE_TOO_LARGE",
                "LibreNMS response exceeded the configured safety limit", 502,
                Map.of("maximum_bytes", maximumBytes));
    }

    public record Instance(UUID id, String baseUrl, String tokenCiphertext, String timezone,
                           int connectTimeoutMs, int readTimeoutMs, String status) {
    }
}
