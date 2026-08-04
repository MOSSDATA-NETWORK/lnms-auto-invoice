package com.autoinvoice.api.system;

import com.autoinvoice.api.idempotency.IdempotencyExecutor;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.api.security.PasswordPolicy;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.audit.AuditService;
import com.autoinvoice.platform.security.SecretCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class SystemAdminCredentialIntegrationTest {
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
    void createAndResetIssueTwentyFourHourTemporaryPasswordsAndInvalidateOldSessions() {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 11);
        String masterKey = Base64.getEncoder().encodeToString(key);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        SecretCipher secretCipher = new SecretCipher(masterKey);
        AuditService audit = new AuditService(jdbc, objectMapper);
        IdempotencyExecutor idempotency = new IdempotencyExecutor(
                jdbc, objectMapper, secretCipher, masterKey);
        SystemAdminController controller = new SystemAdminController(
                jdbc, passwordEncoder, new PasswordPolicy(), idempotency, audit);
        AuthenticatedUser actor = createActor(passwordEncoder);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                actor, null, actor.getAuthorities());
        HttpServletRequest request = mock(HttpServletRequest.class);

        SystemAdminController.UserResponse created = controller.createUser(
                        authentication, "create-user-" + UUID.randomUUID(),
                        new SystemAdminController.UserCreateRequest(
                                "new-operator", "new-operator@example.invalid", "New operator",
                                "Temporary!Pass123", Set.of(), "provision operator"), request)
                .getBody();

        assertThat(created).isNotNull();
        assertThat(created.mustChangePassword()).isTrue();
        assertThat(created.temporaryPasswordExpiresAt()).isBetween(
                OffsetDateTime.now().plusHours(23), OffsetDateTime.now().plusHours(25));
        assertThat(jdbc.sql("SELECT security_version FROM users WHERE id = :id")
                .param("id", created.id()).query(Long.class).single()).isEqualTo(1);

        SystemAdminController.UserResponse reset = controller.resetPassword(
                        authentication, created.id(), "reset-user-" + UUID.randomUUID(), "\"0\"",
                        new SystemAdminController.UserPasswordResetRequest(
                                0, "Another!Temp456", "credential recovery"), request)
                .getBody();

        assertThat(reset).isNotNull();
        assertThat(reset.version()).isEqualTo(1);
        assertThat(reset.mustChangePassword()).isTrue();
        assertThat(reset.temporaryPasswordExpiresAt()).isAfter(created.temporaryPasswordExpiresAt());
        assertThat(jdbc.sql("SELECT security_version FROM users WHERE id = :id")
                .param("id", created.id()).query(Long.class).single()).isEqualTo(2);
        String passwordHash = jdbc.sql("SELECT password_hash FROM users WHERE id = :id")
                .param("id", created.id()).query(String.class).single();
        assertThat(passwordEncoder.matches("Another!Temp456", passwordHash)).isTrue();

        assertThatThrownBy(() -> controller.updateUserStatus(
                authentication, actor.userId(), "disable-last-admin-" + UUID.randomUUID(), "\"0\"",
                new SystemAdminController.UserStatusRequest(0, "DISABLED", "guard test"), request))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("LAST_ACTIVE_ADMIN_REQUIRED"));
    }

    private AuthenticatedUser createActor(PasswordEncoder passwordEncoder) {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        String suffix = tenantId.toString().substring(0, 8);
        String tenantCode = "admin-" + suffix;
        jdbc.sql("""
                        INSERT INTO tenants(id, tenant_code, tenant_name)
                        VALUES (:id, :code, 'System admin test')
                        """)
                .param("id", tenantId)
                .param("code", tenantCode)
                .update();
        jdbc.sql("""
                        INSERT INTO users(
                            id, tenant_id, username, email, display_name, password_hash, status
                        ) VALUES (
                            :id, :tenantId, 'admin', :email, 'Administrator', :passwordHash, 'ACTIVE'
                        )
                        """)
                .param("id", userId)
                .param("tenantId", tenantId)
                .param("email", "admin-" + suffix + "@example.invalid")
                .param("passwordHash", passwordEncoder.encode("Administrator!123"))
                .update();
        jdbc.sql("""
                        INSERT INTO roles(id, tenant_id, role_code, role_name, system_role)
                        VALUES (:id, :tenantId, 'ADMIN', 'Administrator', true)
                        """)
                .param("id", roleId)
                .param("tenantId", tenantId)
                .update();
        jdbc.sql("""
                        INSERT INTO role_permissions(tenant_id, role_id, permission_code)
                        VALUES (:tenantId, :roleId, 'system.admin')
                        """)
                .param("tenantId", tenantId)
                .param("roleId", roleId)
                .update();
        jdbc.sql("""
                        INSERT INTO user_roles(tenant_id, user_id, role_id, assigned_by)
                        VALUES (:tenantId, :userId, :roleId, :userId)
                        """)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .param("roleId", roleId)
                .update();
        return new AuthenticatedUser(userId, tenantId, tenantCode, "admin", "Administrator", "",
                false, null, false, 1, Set.of("system.admin"), true);
    }
}
