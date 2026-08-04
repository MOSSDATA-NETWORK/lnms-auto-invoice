package com.autoinvoice.api.security;

import com.autoinvoice.platform.DomainException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MfaEnrollmentProofIntegrationTest {
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
    void proofIsBoundToSessionUserAndSecretVersionAndCanBeConsumedOnlyOnce() {
        AuthenticatedUser actor = createUser();
        MfaEnrollmentProofService proofs = new MfaEnrollmentProofService(jdbc);
        String token = proofs.issue(actor, "session-a", 3);

        assertInvalid(() -> proofs.requireValid(actor, "session-b", token, 3));
        assertInvalid(() -> proofs.requireValid(actor, "session-a", token, 4));

        UUID proofId = proofs.requireValid(actor, "session-a", token, 3);
        AuthenticatedUser otherActor = createUser();
        assertInvalid(() -> proofs.consume(otherActor, proofId));
        assertThat(jdbc.sql("SELECT consumed_at IS NULL FROM mfa_enrollment_proofs WHERE id = :id")
                .param("id", proofId).query(Boolean.class).single()).isTrue();

        proofs.consume(actor, proofId);

        assertInvalid(() -> proofs.requireValid(actor, "session-a", token, 3));
        assertThat(jdbc.sql("""
                        SELECT consumed_at IS NOT NULL FROM mfa_enrollment_proofs WHERE id = :id
                        """).param("id", proofId).query(Boolean.class).single()).isTrue();
    }

    private AuthenticatedUser createUser() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String suffix = tenantId.toString().substring(0, 8);
        jdbc.sql("""
                        INSERT INTO tenants(id, tenant_code, tenant_name)
                        VALUES (:id, :code, 'MFA proof test')
                        """)
                .param("id", tenantId)
                .param("code", "proof-" + suffix)
                .update();
        jdbc.sql("""
                        INSERT INTO users(id, tenant_id, username, email, display_name, status)
                        VALUES (:id, :tenantId, :username, :email, 'Proof user', 'ACTIVE')
                        """)
                .param("id", userId)
                .param("tenantId", tenantId)
                .param("username", "proof-" + suffix)
                .param("email", "proof-" + suffix + "@example.invalid")
                .update();
        return new AuthenticatedUser(userId, tenantId, "proof-" + suffix, "proof-" + suffix,
                "Proof user", "", false, null, false, 1, Set.of(), true);
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("MFA_ENROLLMENT_PROOF_INVALID"));
    }
}
