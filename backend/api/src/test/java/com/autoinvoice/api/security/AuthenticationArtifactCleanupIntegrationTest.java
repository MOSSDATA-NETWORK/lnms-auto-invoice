package com.autoinvoice.api.security;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AuthenticationArtifactCleanupIntegrationTest {
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
    void deletesOnlyTerminalArtifactsOlderThanRetentionInBoundedBatches() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID staleChallengeId = UUID.randomUUID();
        UUID activeChallengeId = UUID.randomUUID();
        UUID staleProofId = UUID.randomUUID();
        UUID activeProofId = UUID.randomUUID();
        String suffix = tenantId.toString().substring(0, 8);

        jdbc.sql("""
                        INSERT INTO tenants(id, tenant_code, tenant_name)
                        VALUES (:id, :code, 'Authentication cleanup test')
                        """)
                .param("id", tenantId)
                .param("code", "auth-cleanup-" + suffix)
                .update();
        jdbc.sql("""
                        INSERT INTO users(id, tenant_id, username, email, display_name, status)
                        VALUES (:id, :tenantId, :username, :email, 'Cleanup user', 'ACTIVE')
                        """)
                .param("id", userId)
                .param("tenantId", tenantId)
                .param("username", "cleanup-" + suffix)
                .param("email", "cleanup-" + suffix + "@example.invalid")
                .update();

        jdbc.sql("""
                        INSERT INTO authentication_rate_limits(
                            bucket_type, bucket_key_hash, failure_count, window_started_at, updated_at
                        ) VALUES
                            ('LOGIN_IP', repeat('a', 64), 1, now() - interval '26 hours',
                             now() - interval '25 hours'),
                            ('LOGIN_IP', repeat('b', 64), 1, now(), now())
                        """).update();
        jdbc.sql("""
                        INSERT INTO mfa_login_challenges(
                            id, tenant_id, user_id, session_binding_hash, expires_at,
                            consumed_at, updated_at
                        ) VALUES
                            (:staleId, :tenantId, :userId, repeat('c', 64),
                             now() - interval '25 hours', now() - interval '25 hours',
                             now() - interval '25 hours'),
                            (:activeId, :tenantId, :userId, repeat('d', 64),
                             now() + interval '5 minutes', NULL, now())
                        """)
                .param("staleId", staleChallengeId)
                .param("activeId", activeChallengeId)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .update();
        jdbc.sql("""
                        INSERT INTO mfa_enrollment_proofs(
                            id, tenant_id, user_id, proof_hash, session_binding_hash,
                            secret_version, expires_at, consumed_at, updated_at
                        ) VALUES
                            (:staleId, :tenantId, :userId, repeat('e', 64), repeat('f', 64),
                             1, now() - interval '25 hours', now() - interval '25 hours',
                             now() - interval '25 hours'),
                            (:activeId, :tenantId, :userId, repeat('1', 64), repeat('2', 64),
                             2, now() + interval '5 minutes', NULL, now())
                        """)
                .param("staleId", staleProofId)
                .param("activeId", activeProofId)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .update();

        AuthenticationArtifactCleanupService.CleanupResult result =
                new AuthenticationArtifactCleanupService(jdbc).cleanupExpiredArtifacts();

        assertThat(result).isEqualTo(new AuthenticationArtifactCleanupService.CleanupResult(1, 1, 1));
        assertThat(jdbc.sql("SELECT count(*) FROM authentication_rate_limits")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT id FROM mfa_login_challenges")
                .query(UUID.class).single()).isEqualTo(activeChallengeId);
        assertThat(jdbc.sql("SELECT id FROM mfa_enrollment_proofs")
                .query(UUID.class).single()).isEqualTo(activeProofId);
    }
}
