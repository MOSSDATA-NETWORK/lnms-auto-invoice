package com.autoinvoice.api.security;

import com.autoinvoice.platform.security.SecretCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MfaChallengeReplayIntegrationTest {
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final String CODE = "287082";

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
    void sameTotpCounterCannotBeUsedByANewChallenge() {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        SecretCipher cipher = new SecretCipher(Base64.getEncoder().encodeToString(key));
        AuthenticatedUser actor = createUser(cipher);
        TotpService totp = new TotpService(Clock.fixed(Instant.ofEpochSecond(59), ZoneOffset.UTC));
        MfaChallengeService challenges = new MfaChallengeService(
                jdbc, totp, cipher, new ObjectMapper(), new DatabaseUserDetailsService(jdbc));

        MfaChallengeService.PendingChallenge first = challenges.create(actor, "session-one");
        MfaChallengeService.Verification accepted = challenges.verify(first, "session-one", CODE);
        MfaChallengeService.PendingChallenge second = challenges.create(actor, "session-two");
        MfaChallengeService.Verification replay = challenges.verify(second, "session-two", CODE);

        assertThat(accepted.verified()).isTrue();
        assertThat(replay.verified()).isFalse();
        assertThat(jdbc.sql("SELECT mfa_last_accepted_counter FROM users WHERE id = :id")
                .param("id", actor.userId()).query(Long.class).single()).isEqualTo(1);
    }

    private AuthenticatedUser createUser(SecretCipher cipher) {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String suffix = tenantId.toString().substring(0, 8);
        String tenantCode = "replay-" + suffix;
        String username = "replay-" + suffix;
        String ciphertext = cipher.encrypt(SECRET, tenantId, "user-mfa:" + userId);
        jdbc.sql("""
                        INSERT INTO tenants(id, tenant_code, tenant_name)
                        VALUES (:id, :code, 'MFA replay test')
                        """)
                .param("id", tenantId)
                .param("code", tenantCode)
                .update();
        jdbc.sql("""
                        INSERT INTO users(
                            id, tenant_id, username, email, display_name, status,
                            mfa_enabled, mfa_secret_ciphertext
                        ) VALUES (
                            :id, :tenantId, :username, :email, 'Replay user', 'ACTIVE', true, :ciphertext
                        )
                        """)
                .param("id", userId)
                .param("tenantId", tenantId)
                .param("username", username)
                .param("email", username + "@example.invalid")
                .param("ciphertext", ciphertext)
                .update();
        return new AuthenticatedUser(userId, tenantId, tenantCode, username, "Replay user", "",
                true, null, false, 1, Set.of(), true);
    }
}
