package com.autoinvoice.api.librenms;

import com.autoinvoice.api.idempotency.IdempotencyExecutor;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.platform.audit.AuditService;
import com.autoinvoice.platform.jobs.BackgroundJobService;
import com.autoinvoice.platform.security.SecretCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class LibrenmsControllerIdempotencyIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.9-alpine");

    private static JdbcClient jdbc;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
    }

    @Test
    void verifyReplaysAcceptedJobAfterTheInstanceVersionChanges() {
        Fixture fixture = createInstance();
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        UUID jobId = UUID.randomUUID();
        when(jobs.enqueue(any(UUID.class), anyString(), anyString(), any())).thenReturn(jobId);
        LibrenmsController controller = controller(jobs);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                fixture.actor(), null, fixture.actor().getAuthorities());
        String key = "verify-librenms-" + UUID.randomUUID();

        LibrenmsController.JobAccepted first = controller.verify(
                authentication, fixture.instanceId(), key).getBody();
        jdbc.sql("UPDATE librenms_instances SET version = version + 1 WHERE id = :id")
                .param("id", fixture.instanceId()).update();
        LibrenmsController.JobAccepted replay = controller.verify(
                authentication, fixture.instanceId(), key).getBody();

        assertThat(first).isNotNull();
        assertThat(replay).isEqualTo(first);
        assertThat(replay.jobId()).isEqualTo(jobId);
        verify(jobs, times(1)).enqueue(any(UUID.class), anyString(), anyString(), any());
    }

    private LibrenmsController controller(BackgroundJobService jobs) {
        byte[] rawKey = new byte[32];
        java.util.Arrays.fill(rawKey, (byte) 31);
        String masterKey = Base64.getEncoder().encodeToString(rawKey);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        return new LibrenmsController(jdbc, new SecretCipher(masterKey),
                new IdempotencyExecutor(jdbc, objectMapper, new SecretCipher(masterKey), masterKey),
                jobs, objectMapper, new AuditService(jdbc, objectMapper), null);
    }

    private Fixture createInstance() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID instanceId = UUID.randomUUID();
        String suffix = tenantId.toString().substring(0, 8);
        String tenantCode = "librenms-" + suffix;
        String username = "operator-" + suffix;
        jdbc.sql("INSERT INTO tenants(id, tenant_code, tenant_name) VALUES (:id, :code, 'LibreNMS test')")
                .param("id", tenantId).param("code", tenantCode).update();
        jdbc.sql("""
                        INSERT INTO users(id, tenant_id, username, email, display_name, status)
                        VALUES (:id, :tenantId, :username, :email, 'LibreNMS operator', 'ACTIVE')
                        """)
                .param("id", userId).param("tenantId", tenantId).param("username", username)
                .param("email", username + "@example.invalid").update();
        jdbc.sql("""
                        INSERT INTO librenms_instances(
                            id, tenant_id, instance_code, instance_name, base_url, api_token_ciphertext,
                            timezone, connect_timeout_ms, read_timeout_ms, max_concurrency, tls_verify, status
                        ) VALUES (
                            :id, :tenantId, :code, 'LibreNMS replay test', 'https://librenms.example.invalid',
                            'ciphertext', 'Asia/Shanghai', 5000, 30000, 4, true, 'ACTIVE'
                        )
                        """)
                .param("id", instanceId).param("tenantId", tenantId).param("code", "lnms-" + suffix).update();
        AuthenticatedUser actor = new AuthenticatedUser(userId, tenantId, tenantCode, username,
                "LibreNMS operator", "", false, null, false, 1,
                Set.of("usage.sync"), true);
        return new Fixture(actor, instanceId);
    }

    private record Fixture(AuthenticatedUser actor, UUID instanceId) {
    }
}
