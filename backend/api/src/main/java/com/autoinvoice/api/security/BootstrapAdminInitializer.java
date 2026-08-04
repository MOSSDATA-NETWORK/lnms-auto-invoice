package com.autoinvoice.api.security;

import com.autoinvoice.platform.UuidV7;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Component
public class BootstrapAdminInitializer implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final boolean enabled;
    private final String password;

    public BootstrapAdminInitializer(JdbcClient jdbc, PasswordEncoder passwordEncoder,
                                     PlatformTransactionManager transactionManager,
                                     @Value("${auto-invoice.bootstrap.enabled:false}") boolean enabled,
                                     @Value("${auto-invoice.bootstrap.admin-password:}") String password) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (password == null || password.length() < 12) {
            throw new IllegalStateException("Bootstrap administrator password must contain at least 12 characters");
        }
        transactionTemplate.executeWithoutResult(ignored -> bootstrapAdministrator());
    }

    private void bootstrapAdministrator() {
        if (jdbc.sql("SELECT count(*) FROM tenants").query(Long.class).single() > 0) {
            return;
        }

        UUID tenantId = UuidV7.generate();
        UUID userId = UuidV7.generate();
        UUID roleId = UuidV7.generate();
        jdbc.sql("""
                        INSERT INTO tenants(id, tenant_code, tenant_name)
                        VALUES (:id, 'default', 'Auto Invoice')
                        """).param("id", tenantId).update();
        jdbc.sql("""
                        INSERT INTO users(
                            id, tenant_id, username, email, display_name, password_hash, status,
                            must_change_password, temporary_password_expires_at
                        ) VALUES (
                            :id, :tenantId, 'admin', 'admin@localhost', '系统管理员', :passwordHash, 'ACTIVE',
                            true, now() + interval '24 hours'
                        )
                        """)
                .param("id", userId)
                .param("tenantId", tenantId)
                .param("passwordHash", passwordEncoder.encode(password))
                .update();
        jdbc.sql("""
                        INSERT INTO roles(id, tenant_id, role_code, role_name, system_role)
                        VALUES (:id, :tenantId, 'ADMIN', '系统管理员', true)
                        """).param("id", roleId).param("tenantId", tenantId).update();
        jdbc.sql("""
                        INSERT INTO role_permissions(tenant_id, role_id, permission_code)
                        SELECT :tenantId, :roleId, permission_code FROM permissions
                        """).param("tenantId", tenantId).param("roleId", roleId).update();
        jdbc.sql("""
                        INSERT INTO user_roles(tenant_id, user_id, role_id, assigned_by)
                        VALUES (:tenantId, :userId, :roleId, :userId)
                        """)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .param("roleId", roleId)
                .update();
        jdbc.sql("""
                        INSERT INTO tenant_operational_settings(tenant_id, system_user_id, updated_by)
                        VALUES (:tenantId, :userId, :userId)
                        """)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .update();
    }
}
