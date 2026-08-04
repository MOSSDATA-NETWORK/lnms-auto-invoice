package com.autoinvoice.api.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final JdbcClient jdbc;

    public DatabaseUserDetailsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserDetails loadUserByUsername(String tenantAndUsername) throws UsernameNotFoundException {
        String[] parts = tenantAndUsername.split(":", 2);
        if (parts.length != 2) {
            throw new UsernameNotFoundException("Tenant and username are required");
        }
        return jdbc.sql("""
                        SELECT u.id, u.tenant_id, t.tenant_code, u.username, u.display_name,
                               u.password_hash, u.mfa_enabled, u.mfa_secret_ciphertext,
                               u.security_version, u.status, u.must_change_password,
                               (NOT u.must_change_password OR
                                (u.temporary_password_expires_at IS NOT NULL
                                 AND u.temporary_password_expires_at > now())) AS credentials_non_expired
                        FROM users u
                        JOIN tenants t ON t.id = u.tenant_id
                        WHERE lower(t.tenant_code) = lower(:tenantCode)
                          AND t.status = 'ACTIVE'
                          AND (lower(u.username) = lower(:username) OR lower(u.email) = lower(:username))
                        ORDER BY CASE WHEN lower(u.username) = lower(:username) THEN 0 ELSE 1 END
                        LIMIT 1
                        """)
                .param("tenantCode", parts[0])
                .param("username", parts[1])
                .query((rs, rowNum) -> map(rs, true))
                .optional()
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    public Optional<AuthenticatedUser> findCurrent(UUID tenantId, UUID userId) {
        return jdbc.sql("""
                        SELECT u.id, u.tenant_id, t.tenant_code, u.username, u.display_name,
                               u.password_hash, u.mfa_enabled, u.mfa_secret_ciphertext,
                               u.security_version, u.status, u.must_change_password,
                               (NOT u.must_change_password OR
                                (u.temporary_password_expires_at IS NOT NULL
                                 AND u.temporary_password_expires_at > now())) AS credentials_non_expired
                        FROM users u
                        JOIN tenants t ON t.id = u.tenant_id
                        WHERE u.tenant_id = :tenantId AND u.id = :userId
                          AND t.status = 'ACTIVE'
                        """)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .query((rs, rowNum) -> map(rs, false))
                .optional();
    }

    private AuthenticatedUser map(ResultSet rs, boolean includePassword) throws SQLException {
        UUID userId = rs.getObject("id", UUID.class);
        UUID tenantId = rs.getObject("tenant_id", UUID.class);
        Set<String> permissions = new HashSet<>(jdbc.sql("""
                        SELECT DISTINCT rp.permission_code
                        FROM user_roles ur
                        JOIN role_permissions rp
                          ON rp.tenant_id = ur.tenant_id AND rp.role_id = ur.role_id
                        WHERE ur.tenant_id = :tenantId AND ur.user_id = :userId
                        """)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .query(String.class)
                .list());
        return new AuthenticatedUser(
                userId,
                tenantId,
                rs.getString("tenant_code"),
                rs.getString("username"),
                rs.getString("display_name"),
                includePassword ? rs.getString("password_hash") : "",
                rs.getBoolean("mfa_enabled"),
                rs.getString("mfa_secret_ciphertext"),
                false,
                rs.getLong("security_version"),
                Set.copyOf(permissions),
                "ACTIVE".equals(rs.getString("status")),
                rs.getBoolean("credentials_non_expired"),
                rs.getBoolean("must_change_password")
        );
    }
}
