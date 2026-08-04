package com.autoinvoice.api.security;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class DatabaseUserDetailsSecurityIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.9-alpine");

    private static JdbcClient jdbc;
    private static DatabaseUserDetailsService userDetails;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        userDetails = new DatabaseUserDetailsService(jdbc);
    }

    @Test
    void suspendedTenantRejectsNewLoginAndRemovesTheCurrentSessionIdentity() {
        Fixture fixture = createUser(false, "NULL");
        assertThat(userDetails.loadUserByUsername(fixture.tenantCode() + ":" + fixture.username()))
                .isInstanceOf(AuthenticatedUser.class);
        assertThat(userDetails.findCurrent(fixture.tenantId(), fixture.userId())).isPresent();

        jdbc.sql("UPDATE tenants SET status = 'SUSPENDED' WHERE id = :id")
                .param("id", fixture.tenantId()).update();

        assertThatThrownBy(() -> userDetails.loadUserByUsername(
                fixture.tenantCode() + ":" + fixture.username()))
                .isInstanceOf(UsernameNotFoundException.class);
        assertThat(userDetails.findCurrent(fixture.tenantId(), fixture.userId())).isEmpty();
    }

    @Test
    void expiredTemporaryPasswordIsReportedAsExpiredCredentials() {
        Fixture fixture = createUser(true, "now() - interval '1 minute'");

        AuthenticatedUser loginUser = (AuthenticatedUser) userDetails.loadUserByUsername(
                fixture.tenantCode() + ":" + fixture.username());
        AuthenticatedUser current = userDetails.findCurrent(fixture.tenantId(), fixture.userId()).orElseThrow();

        assertThat(loginUser.mustChangePassword()).isTrue();
        assertThat(loginUser.credentialsNonExpired()).isFalse();
        assertThat(current.credentialsNonExpired()).isFalse();
    }

    private Fixture createUser(boolean mustChangePassword, String expirySql) {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String suffix = tenantId.toString().substring(0, 8);
        String tenantCode = "security-" + suffix;
        String username = "user-" + suffix;
        jdbc.sql("""
                        INSERT INTO tenants(id, tenant_code, tenant_name)
                        VALUES (:id, :code, 'Security integration test')
                        """)
                .param("id", tenantId)
                .param("code", tenantCode)
                .update();
        jdbc.sql("""
                        INSERT INTO users(
                            id, tenant_id, username, email, display_name, password_hash, status,
                            must_change_password, temporary_password_expires_at
                        ) VALUES (
                            :id, :tenantId, :username, :email, 'Security user', 'test-hash', 'ACTIVE',
                            :mustChangePassword, %s
                        )
                        """.formatted(expirySql))
                .param("id", userId)
                .param("tenantId", tenantId)
                .param("username", username)
                .param("email", username + "@example.invalid")
                .param("mustChangePassword", mustChangePassword)
                .update();
        return new Fixture(tenantId, userId, tenantCode, username);
    }

    private record Fixture(UUID tenantId, UUID userId, String tenantCode, String username) {
    }
}
