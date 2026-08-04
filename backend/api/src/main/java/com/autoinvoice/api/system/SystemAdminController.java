package com.autoinvoice.api.system;

import com.autoinvoice.api.http.VersionEtag;
import com.autoinvoice.api.idempotency.IdempotencyExecutor;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.api.security.PasswordPolicy;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/system")
@PreAuthorize("hasAuthority('system.admin')")
public class SystemAdminController {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final IdempotencyExecutor idempotency;
    private final AuditService audit;

    public SystemAdminController(JdbcClient jdbc, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
                                 IdempotencyExecutor idempotency, AuditService audit) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @GetMapping("/users")
    public List<UserResponse> users(Authentication authentication) {
        UUID tenantId = principal(authentication).tenantId();
        List<UserBase> users = jdbc.sql("SELECT * FROM users WHERE tenant_id = :tenantId ORDER BY username")
                .param("tenantId", tenantId).query(this::mapUserBase).list();
        return users.stream().map(user -> new UserResponse(user.id(), user.username(), user.email(),
                user.displayName(), user.mfaEnabled(), user.status(), rolesForUser(tenantId, user.id()),
                user.mustChangePassword(), user.temporaryPasswordExpiresAt(),
                user.lastLoginAt(), user.createdAt(), user.version())).toList();
    }

    @PostMapping("/users")
    public ResponseEntity<UserResponse> createUser(Authentication authentication,
                                                   @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                   @Valid @RequestBody UserCreateRequest request,
                                                   HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", "/api/v1/system/users", request,
                UserResponse.class, () -> {
                    validateRoles(actor.tenantId(), request.roleIds());
                    passwordPolicy.validate(request.username(), request.temporaryPassword());
                    UUID userId = UuidV7.generate();
                    jdbc.sql("""
                                    INSERT INTO users(
                                        id, tenant_id, username, email, display_name,
                                        password_hash, status, must_change_password,
                                        temporary_password_expires_at
                                    ) VALUES (
                                        :id, :tenantId, :username, :email, :displayName,
                                        :passwordHash, 'ACTIVE', true, now() + interval '24 hours'
                                    )
                                    """)
                            .param("id", userId).param("tenantId", actor.tenantId())
                            .param("username", request.username()).param("email", request.email())
                            .param("displayName", request.displayName())
                            .param("passwordHash", passwordEncoder.encode(request.temporaryPassword())).update();
                    assignRoles(actor.tenantId(), userId, request.roleIds(), actor.userId());
                    UserResponse created = findUser(actor.tenantId(), userId);
                    record(actor, "user.created", "user", userId, null, created, request.reason(), servletRequest);
                    return ResponseEntity.status(HttpStatus.CREATED).eTag(VersionEtag.format(0)).body(created);
                });
    }

    @PostMapping("/users/{userId}/reset-password")
    public ResponseEntity<UserResponse> resetPassword(Authentication authentication, @PathVariable UUID userId,
                                                       @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                       @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                       @Valid @RequestBody UserPasswordResetRequest request,
                                                       HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        assertVersion(ifMatch, request.expectedVersion());
        String path = "/api/v1/system/users/" + userId + "/reset-password";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                UserResponse.class, () -> {
                    UserResponse before = findUser(actor.tenantId(), userId);
                    passwordPolicy.validate(before.username(), request.temporaryPassword());
                    int updated = jdbc.sql("""
                                    UPDATE users
                                    SET password_hash = :passwordHash,
                                        must_change_password = true,
                                        temporary_password_expires_at = now() + interval '24 hours',
                                        updated_at = now(), version = version + 1
                                    WHERE tenant_id = :tenantId AND id = :id AND version = :version
                                    """)
                            .param("passwordHash", passwordEncoder.encode(request.temporaryPassword()))
                            .param("tenantId", actor.tenantId())
                            .param("id", userId)
                            .param("version", request.expectedVersion())
                            .update();
                    if (updated != 1) {
                        throw versionConflict(request.expectedVersion());
                    }
                    UserResponse after = findUser(actor.tenantId(), userId);
                    record(actor, "user.temporary_password_reset", "user", userId,
                            before, after, request.reason(), servletRequest);
                    return ResponseEntity.ok().eTag(VersionEtag.format(after.version())).body(after);
                });
    }

    @PostMapping("/users/{userId}/status")
    public ResponseEntity<UserResponse> updateUserStatus(Authentication authentication, @PathVariable UUID userId,
                                                         @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                         @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                         @Valid @RequestBody UserStatusRequest request,
                                                         HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        assertVersion(ifMatch, request.expectedVersion());
        String path = "/api/v1/system/users/" + userId + "/status";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                UserResponse.class, () -> {
            lockTenant(actor.tenantId());
            UserResponse before = findUser(actor.tenantId(), userId);
            if ("ACTIVE".equals(before.status()) && !"ACTIVE".equals(request.status())
                    && userHasSystemAdmin(actor.tenantId(), userId)
                    && activeSystemAdminsExcluding(actor.tenantId(), userId) == 0) {
                throw lastActiveAdminRequired();
            }
            int updated = jdbc.sql("""
                            UPDATE users SET status = :status, updated_at = now(), version = version + 1
                            WHERE tenant_id = :tenantId AND id = :id AND version = :version
                            """)
                    .param("status", request.status()).param("tenantId", actor.tenantId()).param("id", userId)
                    .param("version", request.expectedVersion()).update();
            if (updated != 1) {
                throw versionConflict(request.expectedVersion());
            }
            UserResponse after = findUser(actor.tenantId(), userId);
            record(actor, "user.status_changed", "user", userId, before, after, request.reason(), servletRequest);
            return ResponseEntity.ok().eTag(VersionEtag.format(after.version())).body(after);
        });
    }

    @PostMapping("/users/{userId}/roles")
    public ResponseEntity<UserResponse> updateUserRoles(Authentication authentication, @PathVariable UUID userId,
                                                        @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                        @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                        @Valid @RequestBody UserRolesRequest request,
                                                        HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        assertVersion(ifMatch, request.expectedVersion());
        String path = "/api/v1/system/users/" + userId + "/roles";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                UserResponse.class, () -> {
            lockTenant(actor.tenantId());
            UserResponse before = findUser(actor.tenantId(), userId);
            validateRoles(actor.tenantId(), request.roleIds());
            if ("ACTIVE".equals(before.status()) && userHasSystemAdmin(actor.tenantId(), userId)
                    && !rolesGrantSystemAdmin(actor.tenantId(), request.roleIds())
                    && activeSystemAdminsExcluding(actor.tenantId(), userId) == 0) {
                throw lastActiveAdminRequired();
            }
            int locked = jdbc.sql("""
                            UPDATE users
                            SET updated_at = now(), version = version + 1,
                                security_version = security_version + 1
                            WHERE tenant_id = :tenantId AND id = :id AND version = :version
                            """)
                    .param("tenantId", actor.tenantId()).param("id", userId)
                    .param("version", request.expectedVersion()).update();
            if (locked != 1) {
                throw versionConflict(request.expectedVersion());
            }
            jdbc.sql("DELETE FROM user_roles WHERE tenant_id = :tenantId AND user_id = :userId")
                    .param("tenantId", actor.tenantId()).param("userId", userId).update();
            assignRoles(actor.tenantId(), userId, request.roleIds(), actor.userId());
            UserResponse after = findUser(actor.tenantId(), userId);
            record(actor, "user.roles_changed", "user", userId, before, after, request.reason(), servletRequest);
            return ResponseEntity.ok().eTag(VersionEtag.format(after.version())).body(after);
        });
    }

    @GetMapping("/permissions")
    public List<PermissionResponse> permissions() {
        return jdbc.sql("SELECT permission_code, description FROM permissions ORDER BY permission_code")
                .query((rs, row) -> new PermissionResponse(rs.getString(1), rs.getString(2))).list();
    }

    @GetMapping("/roles")
    public List<RoleResponse> roles(Authentication authentication) {
        UUID tenantId = principal(authentication).tenantId();
        List<RoleBase> roles = jdbc.sql("SELECT * FROM roles WHERE tenant_id = :tenantId ORDER BY role_code")
                .param("tenantId", tenantId).query(this::mapRoleBase).list();
        return roles.stream().map(role -> new RoleResponse(role.id(), role.roleCode(), role.roleName(),
                role.systemRole(), permissionsForRole(tenantId, role.id()), role.createdAt(), role.updatedAt(),
                role.version())).toList();
    }

    @PostMapping("/roles")
    public ResponseEntity<RoleResponse> createRole(Authentication authentication,
                                                   @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                   @Valid @RequestBody RoleCreateRequest request,
                                                   HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", "/api/v1/system/roles", request,
                RoleResponse.class, () -> {
                    validatePermissions(request.permissions());
                    UUID roleId = UuidV7.generate();
                    jdbc.sql("""
                                    INSERT INTO roles(id, tenant_id, role_code, role_name, system_role)
                                    VALUES (:id, :tenantId, :code, :name, false)
                                    """)
                            .param("id", roleId).param("tenantId", actor.tenantId())
                            .param("code", request.roleCode()).param("name", request.roleName()).update();
                    assignPermissions(actor.tenantId(), roleId, request.permissions());
                    RoleResponse created = findRole(actor.tenantId(), roleId);
                    record(actor, "role.created", "role", roleId, null, created, request.reason(), servletRequest);
                    return ResponseEntity.status(HttpStatus.CREATED).eTag(VersionEtag.format(0)).body(created);
                });
    }

    @PostMapping("/roles/{roleId}")
    public ResponseEntity<RoleResponse> updateRole(Authentication authentication, @PathVariable UUID roleId,
                                                   @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                   @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                   @Valid @RequestBody RoleUpdateRequest request,
                                                   HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        assertVersion(ifMatch, request.expectedVersion());
        String path = "/api/v1/system/roles/" + roleId;
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                RoleResponse.class, () -> {
            lockTenant(actor.tenantId());
            RoleResponse before = findRole(actor.tenantId(), roleId);
            validatePermissions(request.permissions());
            if (before.permissions().contains("system.admin")
                    && !request.permissions().contains("system.admin")
                    && activeSystemAdminsThroughOtherRoles(actor.tenantId(), roleId) == 0) {
                throw lastActiveAdminRequired();
            }
            int updated = jdbc.sql("""
                            UPDATE roles SET role_name = :name, updated_at = now(), version = version + 1
                            WHERE tenant_id = :tenantId AND id = :id AND version = :version
                            """)
                    .param("name", request.roleName()).param("tenantId", actor.tenantId()).param("id", roleId)
                    .param("version", request.expectedVersion()).update();
            if (updated != 1) {
                throw versionConflict(request.expectedVersion());
            }
            jdbc.sql("DELETE FROM role_permissions WHERE tenant_id = :tenantId AND role_id = :roleId")
                    .param("tenantId", actor.tenantId()).param("roleId", roleId).update();
            assignPermissions(actor.tenantId(), roleId, request.permissions());
            invalidateRoleSessions(actor.tenantId(), roleId);
            RoleResponse after = findRole(actor.tenantId(), roleId);
            record(actor, "role.updated", "role", roleId, before, after, request.reason(), servletRequest);
            return ResponseEntity.ok().eTag(VersionEtag.format(after.version())).body(after);
        });
    }

    private UserResponse findUser(UUID tenantId, UUID userId) {
        UserBase user = jdbc.sql("SELECT * FROM users WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", userId).query(this::mapUserBase).optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "User was not found", 404,
                        Map.of("user_id", userId)));
        return new UserResponse(user.id(), user.username(), user.email(), user.displayName(), user.mfaEnabled(),
                user.status(), rolesForUser(tenantId, user.id()), user.mustChangePassword(),
                user.temporaryPasswordExpiresAt(), user.lastLoginAt(), user.createdAt(), user.version());
    }

    private RoleResponse findRole(UUID tenantId, UUID roleId) {
        RoleBase role = jdbc.sql("SELECT * FROM roles WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", roleId).query(this::mapRoleBase).optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Role was not found", 404,
                        Map.of("role_id", roleId)));
        return new RoleResponse(role.id(), role.roleCode(), role.roleName(), role.systemRole(),
                permissionsForRole(tenantId, role.id()), role.createdAt(), role.updatedAt(), role.version());
    }

    private UserBase mapUserBase(ResultSet rs, int row) throws SQLException {
        return new UserBase(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("email"),
                rs.getString("display_name"), rs.getBoolean("mfa_enabled"), rs.getString("status"),
                rs.getBoolean("must_change_password"),
                rs.getObject("temporary_password_expires_at", OffsetDateTime.class),
                rs.getObject("last_login_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class),
                rs.getLong("version"));
    }

    private RoleBase mapRoleBase(ResultSet rs, int row) throws SQLException {
        return new RoleBase(rs.getObject("id", UUID.class), rs.getString("role_code"), rs.getString("role_name"),
                rs.getBoolean("system_role"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class), rs.getLong("version"));
    }

    private List<RoleReference> rolesForUser(UUID tenantId, UUID userId) {
        return jdbc.sql("""
                        SELECT role.id, role.role_code, role.role_name
                        FROM user_roles user_role JOIN roles role
                          ON role.tenant_id = user_role.tenant_id AND role.id = user_role.role_id
                        WHERE user_role.tenant_id = :tenantId AND user_role.user_id = :userId
                        ORDER BY role.role_code
                        """)
                .param("tenantId", tenantId).param("userId", userId)
                .query((rs, row) -> new RoleReference(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .list();
    }

    private Set<String> permissionsForRole(UUID tenantId, UUID roleId) {
        return Set.copyOf(jdbc.sql("""
                        SELECT permission_code FROM role_permissions
                        WHERE tenant_id = :tenantId AND role_id = :roleId ORDER BY permission_code
                        """)
                .param("tenantId", tenantId).param("roleId", roleId).query(String.class).list());
    }

    private void validateRoles(UUID tenantId, Set<UUID> roleIds) {
        if (roleIds.isEmpty()) {
            return;
        }
        Set<UUID> existing = new HashSet<>(jdbc.sql("SELECT id FROM roles WHERE tenant_id = :tenantId AND id IN (:ids)")
                .param("tenantId", tenantId).param("ids", roleIds).query(UUID.class).list());
        if (!existing.equals(roleIds)) {
            throw new DomainException("ROLE_NOT_FOUND", "One or more roles do not belong to the current tenant", 422,
                    Map.of());
        }
    }

    private void validatePermissions(Set<String> permissions) {
        if (permissions.isEmpty()) {
            return;
        }
        Set<String> existing = new HashSet<>(jdbc.sql("SELECT permission_code FROM permissions WHERE permission_code IN (:codes)")
                .param("codes", permissions).query(String.class).list());
        if (!existing.equals(permissions)) {
            throw new DomainException("PERMISSION_NOT_FOUND", "One or more permission codes are unknown", 422,
                    Map.of());
        }
    }

    private void assignRoles(UUID tenantId, UUID userId, Set<UUID> roleIds, UUID actorId) {
        for (UUID roleId : roleIds) {
            jdbc.sql("""
                            INSERT INTO user_roles(tenant_id, user_id, role_id, assigned_by)
                            VALUES (:tenantId, :userId, :roleId, :actorId) ON CONFLICT DO NOTHING
                            """)
                    .param("tenantId", tenantId).param("userId", userId).param("roleId", roleId)
                    .param("actorId", actorId).update();
        }
    }

    private void assignPermissions(UUID tenantId, UUID roleId, Set<String> permissions) {
        for (String permission : permissions) {
            jdbc.sql("""
                            INSERT INTO role_permissions(tenant_id, role_id, permission_code)
                            VALUES (:tenantId, :roleId, :permission) ON CONFLICT DO NOTHING
                            """)
                    .param("tenantId", tenantId).param("roleId", roleId).param("permission", permission).update();
        }
    }

    private void invalidateRoleSessions(UUID tenantId, UUID roleId) {
        jdbc.sql("""
                        UPDATE users app_user
                        SET security_version = security_version + 1, updated_at = now()
                        WHERE app_user.tenant_id = :tenantId
                          AND EXISTS (
                              SELECT 1 FROM user_roles user_role
                              WHERE user_role.tenant_id = app_user.tenant_id
                                AND user_role.user_id = app_user.id
                                AND user_role.role_id = :roleId
                          )
                        """)
                .param("tenantId", tenantId)
                .param("roleId", roleId)
                .update();
    }

    private void lockTenant(UUID tenantId) {
        jdbc.sql("SELECT id FROM tenants WHERE id = :tenantId FOR UPDATE")
                .param("tenantId", tenantId)
                .query(UUID.class)
                .single();
    }

    private boolean userHasSystemAdmin(UUID tenantId, UUID userId) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM user_roles user_role
                            JOIN role_permissions role_permission
                              ON role_permission.tenant_id = user_role.tenant_id
                             AND role_permission.role_id = user_role.role_id
                            WHERE user_role.tenant_id = :tenantId
                              AND user_role.user_id = :userId
                              AND role_permission.permission_code = 'system.admin'
                        )
                        """)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .query(Boolean.class)
                .single();
    }

    private boolean rolesGrantSystemAdmin(UUID tenantId, Set<UUID> roleIds) {
        if (roleIds.isEmpty()) {
            return false;
        }
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM role_permissions
                            WHERE tenant_id = :tenantId AND role_id IN (:roleIds)
                              AND permission_code = 'system.admin'
                        )
                        """)
                .param("tenantId", tenantId)
                .param("roleIds", roleIds)
                .query(Boolean.class)
                .single();
    }

    private int activeSystemAdminsExcluding(UUID tenantId, UUID excludedUserId) {
        return jdbc.sql("""
                        SELECT count(DISTINCT app_user.id)
                        FROM users app_user
                        JOIN user_roles user_role
                          ON user_role.tenant_id = app_user.tenant_id
                         AND user_role.user_id = app_user.id
                        JOIN role_permissions role_permission
                          ON role_permission.tenant_id = user_role.tenant_id
                         AND role_permission.role_id = user_role.role_id
                        WHERE app_user.tenant_id = :tenantId
                          AND app_user.status = 'ACTIVE'
                          AND app_user.id <> :excludedUserId
                          AND role_permission.permission_code = 'system.admin'
                        """)
                .param("tenantId", tenantId)
                .param("excludedUserId", excludedUserId)
                .query(Integer.class)
                .single();
    }

    private int activeSystemAdminsThroughOtherRoles(UUID tenantId, UUID excludedRoleId) {
        return jdbc.sql("""
                        SELECT count(DISTINCT app_user.id)
                        FROM users app_user
                        JOIN user_roles user_role
                          ON user_role.tenant_id = app_user.tenant_id
                         AND user_role.user_id = app_user.id
                        JOIN role_permissions role_permission
                          ON role_permission.tenant_id = user_role.tenant_id
                         AND role_permission.role_id = user_role.role_id
                        WHERE app_user.tenant_id = :tenantId
                          AND app_user.status = 'ACTIVE'
                          AND role_permission.permission_code = 'system.admin'
                          AND user_role.role_id <> :excludedRoleId
                        """)
                .param("tenantId", tenantId)
                .param("excludedRoleId", excludedRoleId)
                .query(Integer.class)
                .single();
    }

    private DomainException lastActiveAdminRequired() {
        return new DomainException("LAST_ACTIVE_ADMIN_REQUIRED",
                "At least one active user must retain the system.admin permission", 409, Map.of());
    }

    private void assertVersion(String ifMatch, long bodyVersion) {
        if (VersionEtag.parse(ifMatch) != bodyVersion) {
            throw versionConflict(bodyVersion);
        }
    }

    private DomainException versionConflict(long expectedVersion) {
        return new DomainException("VERSION_CONFLICT", "Resource was modified by another request", 409,
                Map.of("expected_version", expectedVersion));
    }

    private AuthenticatedUser principal(Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    private void record(AuthenticatedUser actor, String action, String type, UUID id, Object before,
                        Object after, String reason, HttpServletRequest request) {
        audit.record(actor.tenantId(), "USER", actor.userId(), actor.displayName(), action, type, id,
                before, after, reason, request.getHeader("X-Request-Id"));
    }

    public record UserCreateRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{2,99}") String username,
            @NotBlank @Email String email, @NotBlank @Size(max = 160) String displayName,
            @NotBlank @Size(min = 12, max = 200) String temporaryPassword,
            @NotNull Set<UUID> roleIds, @NotBlank String reason) {
    }

    public record UserStatusRequest(@PositiveOrZero long expectedVersion,
                                    @NotBlank @Pattern(regexp = "ACTIVE|LOCKED|DISABLED") String status,
                                    @NotBlank String reason) {
    }

    public record UserRolesRequest(@PositiveOrZero long expectedVersion,
                                   @NotNull Set<UUID> roleIds, @NotBlank String reason) {
    }

    public record UserPasswordResetRequest(@PositiveOrZero long expectedVersion,
                                           @NotBlank @Size(min = 12, max = 200) String temporaryPassword,
                                           @NotBlank String reason) {
    }

    public record RoleCreateRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{2,99}") String roleCode,
            @NotBlank @Size(max = 160) String roleName,
            @NotNull Set<String> permissions, @NotBlank String reason) {
    }

    public record RoleUpdateRequest(@PositiveOrZero long expectedVersion,
                                    @NotBlank @Size(max = 160) String roleName,
                                    @NotNull Set<String> permissions, @NotBlank String reason) {
    }

    public record UserResponse(UUID id, String username, String email, String displayName,
                               boolean mfaEnabled, String status, List<RoleReference> roles,
                               boolean mustChangePassword, OffsetDateTime temporaryPasswordExpiresAt,
                               OffsetDateTime lastLoginAt, OffsetDateTime createdAt, long version) {
    }

    public record RoleReference(UUID id, String roleCode, String roleName) {
    }

    public record RoleResponse(UUID id, String roleCode, String roleName, boolean systemRole,
                               Set<String> permissions, OffsetDateTime createdAt,
                               OffsetDateTime updatedAt, long version) {
    }

    public record PermissionResponse(String permissionCode, String description) {
    }

    private record UserBase(UUID id, String username, String email, String displayName, boolean mfaEnabled,
                            String status, boolean mustChangePassword, OffsetDateTime temporaryPasswordExpiresAt,
                            OffsetDateTime lastLoginAt, OffsetDateTime createdAt, long version) {
    }

    private record RoleBase(UUID id, String roleCode, String roleName, boolean systemRole,
                            OffsetDateTime createdAt, OffsetDateTime updatedAt, long version) {
    }
}
