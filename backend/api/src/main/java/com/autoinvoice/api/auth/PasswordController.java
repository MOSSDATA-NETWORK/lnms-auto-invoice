package com.autoinvoice.api.auth;

import com.autoinvoice.api.idempotency.IdempotencyExecutor;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.api.security.AuthenticationThrottleService;
import com.autoinvoice.api.security.PasswordPolicy;
import com.autoinvoice.api.security.SessionSecurityService;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class PasswordController {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuthenticationThrottleService throttleService;
    private final SessionSecurityService sessionSecurityService;
    private final IdempotencyExecutor idempotency;
    private final AuditService audit;

    public PasswordController(JdbcClient jdbc, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
                              AuthenticationThrottleService throttleService,
                              SessionSecurityService sessionSecurityService,
                              IdempotencyExecutor idempotency, AuditService audit) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.throttleService = throttleService;
        this.sessionSecurityService = sessionSecurityService;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @PostMapping("/change-password")
    public ResponseEntity<AuthController.SessionResponse> changePassword(
            Authentication authentication, @RequestHeader(IdempotencyExecutor.HEADER) String key,
            @Valid @RequestBody ChangePasswordRequest request, HttpServletRequest servletRequest) {
        AuthenticatedUser actor = (AuthenticatedUser) authentication.getPrincipal();
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST",
                "/api/v1/auth/change-password", request, AuthController.SessionResponse.class, () -> {
                    AuthenticationThrottleService.LoginAttempt reauthentication = throttleService.loginAttempt(
                            actor.tenantCode(), actor.username(), servletRequest);
                    if (!throttleService.reserveLoginAttempt(reauthentication, servletRequest)) {
                        throw throttleService.throttled();
                    }
                    PasswordState state = jdbc.sql("""
                                    SELECT app_user.password_hash
                                    FROM users app_user
                                    JOIN tenants tenant ON tenant.id = app_user.tenant_id
                                    WHERE app_user.tenant_id = :tenantId AND app_user.id = :userId
                                      AND app_user.status = 'ACTIVE' AND tenant.status = 'ACTIVE'
                                    FOR UPDATE
                                    """)
                            .param("tenantId", actor.tenantId())
                            .param("userId", actor.userId())
                            .query((rs, row) -> new PasswordState(rs.getString("password_hash")))
                            .optional()
                            .orElseThrow(this::currentPasswordInvalid);
                    if (state.passwordHash() == null
                            || !passwordEncoder.matches(request.currentPassword(), state.passwordHash())) {
                        throttleService.recordLoginFailure(reauthentication,
                                "CURRENT_PASSWORD_INVALID", servletRequest);
                        throw currentPasswordInvalid();
                    }
                    throttleService.recordLoginSuccess(reauthentication);
                    passwordPolicy.validate(actor.username(), request.newPassword());
                    if (passwordEncoder.matches(request.newPassword(), state.passwordHash())) {
                        throw new DomainException("PASSWORD_REUSE_NOT_ALLOWED",
                                "The new password must be different from the current password", 422, Map.of());
                    }
                    jdbc.sql("""
                                    UPDATE users
                                    SET password_hash = :passwordHash,
                                        must_change_password = false,
                                        temporary_password_expires_at = NULL,
                                        updated_at = now(), version = version + 1
                                    WHERE tenant_id = :tenantId AND id = :userId
                                    """)
                            .param("passwordHash", passwordEncoder.encode(request.newPassword()))
                            .param("tenantId", actor.tenantId())
                            .param("userId", actor.userId())
                            .update();
                    audit.record(actor.tenantId(), "USER", actor.userId(), actor.displayName(),
                            "user.password_changed", "user", actor.userId(), null,
                            Map.of("must_change_password", false), request.reason(),
                            servletRequest.getHeader("X-Request-Id"));
                    AuthenticatedUser refreshed = sessionSecurityService.refresh(
                            actor, actor.mfaVerified(), servletRequest);
                    return ResponseEntity.ok(AuthController.SessionResponse.from(refreshed));
                });
    }

    private DomainException currentPasswordInvalid() {
        return new DomainException("CURRENT_PASSWORD_INVALID", "Current password is invalid", 401, Map.of());
    }

    public record ChangePasswordRequest(@NotBlank @Size(max = 200) String currentPassword,
                                        @NotBlank @Size(min = 12, max = 200) String newPassword,
                                        @NotBlank @Size(max = 1000) String reason) {
    }

    private record PasswordState(String passwordHash) {
    }
}
