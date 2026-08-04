package com.autoinvoice.api.auth;

import com.autoinvoice.api.idempotency.IdempotencyExecutor;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.api.security.AuthenticationThrottleService;
import com.autoinvoice.api.security.DatabaseUserDetailsService;
import com.autoinvoice.api.security.PasswordPolicy;
import com.autoinvoice.api.security.SessionSecurityService;
import com.autoinvoice.platform.audit.AuditService;
import com.autoinvoice.platform.security.SecretCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PasswordControllerIntegrationTest {
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
    void changePasswordClearsTemporaryStateBumpsSecurityVersionAndRotatesTheSession() {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 13);
        String masterKey = Base64.getEncoder().encodeToString(key);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        AuditService audit = new AuditService(jdbc, objectMapper);
        DatabaseUserDetailsService userDetails = new DatabaseUserDetailsService(jdbc);
        PasswordController controller = new PasswordController(
                jdbc, passwordEncoder, new PasswordPolicy(),
                new AuthenticationThrottleService(jdbc, audit, masterKey),
                new SessionSecurityService(userDetails),
                new IdempotencyExecutor(jdbc, objectMapper, new SecretCipher(masterKey), masterKey), audit);
        AuthenticatedUser actor = createTemporaryUser(passwordEncoder);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                actor, null, actor.getAuthorities());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/change-password");
        String oldSessionId = request.getSession(true).getId();

        AuthController.SessionResponse response = controller.changePassword(
                        authentication, "change-password-" + UUID.randomUUID(),
                        new PasswordController.ChangePasswordRequest(
                                "Temporary!Pass123", "Permanent!Pass456", "first sign-in"), request)
                .getBody();

        assertThat(response).isNotNull();
        assertThat(response.mustChangePassword()).isFalse();
        assertThat(request.getSession(false).getId()).isNotEqualTo(oldSessionId);
        PasswordRow row = jdbc.sql("""
                        SELECT password_hash, must_change_password,
                               temporary_password_expires_at IS NULL AS expiry_cleared,
                               security_version
                        FROM users WHERE id = :id
                        """).param("id", actor.userId())
                .query((rs, ignored) -> new PasswordRow(
                        rs.getString("password_hash"), rs.getBoolean("must_change_password"),
                        rs.getBoolean("expiry_cleared"), rs.getLong("security_version")))
                .single();
        assertThat(passwordEncoder.matches("Permanent!Pass456", row.passwordHash())).isTrue();
        assertThat(row.mustChangePassword()).isFalse();
        assertThat(row.expiryCleared()).isTrue();
        assertThat(row.securityVersion()).isEqualTo(2);
    }

    private AuthenticatedUser createTemporaryUser(PasswordEncoder passwordEncoder) {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String suffix = tenantId.toString().substring(0, 8);
        String tenantCode = "password-" + suffix;
        String username = "operator-" + suffix;
        jdbc.sql("""
                        INSERT INTO tenants(id, tenant_code, tenant_name)
                        VALUES (:id, :code, 'Password change test')
                        """)
                .param("id", tenantId)
                .param("code", tenantCode)
                .update();
        jdbc.sql("""
                        INSERT INTO users(
                            id, tenant_id, username, email, display_name, password_hash, status,
                            must_change_password, temporary_password_expires_at
                        ) VALUES (
                            :id, :tenantId, :username, :email, 'Temporary user', :passwordHash, 'ACTIVE',
                            true, now() + interval '24 hours'
                        )
                        """)
                .param("id", userId)
                .param("tenantId", tenantId)
                .param("username", username)
                .param("email", username + "@example.invalid")
                .param("passwordHash", passwordEncoder.encode("Temporary!Pass123"))
                .update();
        return new AuthenticatedUser(userId, tenantId, tenantCode, username, "Temporary user", "",
                false, null, false, 1, Set.of(), true, true, true);
    }

    private record PasswordRow(String passwordHash, boolean mustChangePassword,
                               boolean expiryCleared, long securityVersion) {
    }
}
