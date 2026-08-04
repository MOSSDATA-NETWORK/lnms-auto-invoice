package com.autoinvoice.api.auth;

import com.autoinvoice.api.http.VersionEtag;
import com.autoinvoice.api.idempotency.IdempotencyExecutor;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.api.security.AuthenticationThrottleService;
import com.autoinvoice.api.security.MfaEnrollmentProofService;
import com.autoinvoice.api.security.SessionSecurityService;
import com.autoinvoice.api.security.TotpService;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.audit.AuditService;
import com.autoinvoice.platform.security.SecretCipher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth/mfa")
public class MfaManagementController {
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final SecretCipher secretCipher;
    private final TotpService totpService;
    private final AuthenticationThrottleService throttleService;
    private final MfaEnrollmentProofService enrollmentProofService;
    private final SessionSecurityService sessionSecurityService;
    private final PasswordEncoder passwordEncoder;
    private final IdempotencyExecutor idempotency;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    public MfaManagementController(JdbcClient jdbc, ObjectMapper objectMapper, SecretCipher secretCipher,
                                   TotpService totpService, AuthenticationThrottleService throttleService,
                                   MfaEnrollmentProofService enrollmentProofService,
                                   SessionSecurityService sessionSecurityService,
                                   PasswordEncoder passwordEncoder, IdempotencyExecutor idempotency,
                                   AuditService audit) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.secretCipher = secretCipher;
        this.totpService = totpService;
        this.throttleService = throttleService;
        this.enrollmentProofService = enrollmentProofService;
        this.sessionSecurityService = sessionSecurityService;
        this.passwordEncoder = passwordEncoder;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @PostMapping("/enrollment")
    public ResponseEntity<EnrollmentResponse> beginEnrollment(
            Authentication authentication, @RequestHeader(IdempotencyExecutor.HEADER) String key,
            @Valid @RequestBody EnrollmentRequest request, HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST",
                "/api/v1/auth/mfa/enrollment", request,
                EnrollmentResponse.class, () -> {
                    AuthenticationThrottleService.LoginAttempt reauthentication = throttleService.loginAttempt(
                            actor.tenantCode(), actor.username(), servletRequest);
                    if (!throttleService.reserveLoginAttempt(reauthentication, servletRequest)) {
                        throw throttleService.throttled();
                    }
                    EnrollmentState current = jdbc.sql("""
                                    SELECT app_user.password_hash, app_user.mfa_enabled
                                    FROM users app_user
                                    JOIN tenants tenant ON tenant.id = app_user.tenant_id
                                    WHERE app_user.tenant_id = :tenantId AND app_user.id = :id
                                      AND app_user.status = 'ACTIVE' AND tenant.status = 'ACTIVE'
                                    FOR UPDATE
                                    """)
                            .param("tenantId", actor.tenantId())
                            .param("id", actor.userId())
                            .query((rs, row) -> new EnrollmentState(
                                    rs.getString("password_hash"), rs.getBoolean("mfa_enabled")))
                            .optional()
                            .orElseThrow(this::currentPasswordInvalid);
                    if (current.passwordHash() == null
                            || !passwordEncoder.matches(request.currentPassword(), current.passwordHash())) {
                        throttleService.recordLoginFailure(reauthentication,
                                "CURRENT_PASSWORD_INVALID", servletRequest);
                        throw currentPasswordInvalid();
                    }
                    throttleService.recordLoginSuccess(reauthentication);
                    if (current.mfaEnabled()) {
                        throw new DomainException("MFA_ALREADY_ENABLED", "MFA is already enabled", 409, Map.of());
                    }
                    String secret = generateBase32Secret(20);
                    String ciphertext = secretCipher.encrypt(secret, actor.tenantId(), "user-mfa:" + actor.userId());
                    long version = jdbc.sql("""
                                    UPDATE users
                                    SET mfa_secret_ciphertext = :secret, mfa_recovery_codes_json = '[]'::jsonb,
                                        mfa_last_accepted_counter = -1,
                                        updated_at = now(), version = version + 1
                                    WHERE tenant_id = :tenantId AND id = :id
                                    RETURNING version
                                    """)
                            .param("secret", ciphertext).param("tenantId", actor.tenantId()).param("id", actor.userId())
                            .query(Long.class).single();
                    String enrollmentProof = enrollmentProofService.issue(actor, sessionId(servletRequest), version);
                    String label = url(actor.tenantCode() + ":" + actor.username());
                    String uri = "otpauth://totp/Auto%20Invoice:" + label + "?secret=" + secret
                            + "&issuer=Auto%20Invoice&algorithm=SHA1&digits=6&period=30";
                    EnrollmentResponse response = new EnrollmentResponse(secret, uri, enrollmentProof, version);
                    record(actor, "mfa.enrollment_started",
                            Map.of("enrollment_started", true, "version", version),
                            request.reason(), servletRequest);
                    return ResponseEntity.ok().eTag(VersionEtag.format(version)).body(response);
                });
    }

    @PostMapping("/confirm")
    public ResponseEntity<RecoveryCodesResponse> confirm(
            Authentication authentication, @RequestHeader(IdempotencyExecutor.HEADER) String key,
            @Valid @RequestBody ConfirmEnrollmentRequest request, HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST",
                "/api/v1/auth/mfa/confirm", request,
                RecoveryCodesResponse.class, () -> {
                    MfaState state = load(actor);
                    if (state.enabled()) {
                        throw new DomainException("MFA_ALREADY_ENABLED", "MFA is already enabled", 409, Map.of());
                    }
                    UUID proofId = enrollmentProofService.requireValid(actor, sessionId(servletRequest),
                            request.enrollmentProof(), state.version());
                    String secret = decrypt(actor, state.ciphertext());
                    long acceptedCounter = requireValidCode(actor, secret, request.code(), state.lastAcceptedCounter(),
                            servletRequest,
                            "auth.mfa_enrollment_confirm_failed");
                    List<String> codes = generateRecoveryCodes();
                    List<String> hashes = codes.stream().map(code -> recoveryHash(actor, code)).toList();
                    long version = jdbc.sql("""
                                    UPDATE users
                                    SET mfa_enabled = true, mfa_recovery_codes_json = CAST(:codes AS jsonb),
                                        mfa_last_accepted_counter = :acceptedCounter,
                                        updated_at = now(), version = version + 1
                                    WHERE tenant_id = :tenantId AND id = :id
                                    RETURNING version
                                    """)
                            .param("codes", json(hashes)).param("acceptedCounter", acceptedCounter)
                            .param("tenantId", actor.tenantId()).param("id", actor.userId())
                            .query(Long.class).single();
                    enrollmentProofService.consume(actor, proofId);
                    RecoveryCodesResponse response = new RecoveryCodesResponse(codes, version);
                    record(actor, "mfa.enabled", Map.of("enabled", true), request.reason(), servletRequest);
                    sessionSecurityService.refresh(actor, true, servletRequest);
                    return ResponseEntity.ok().eTag(VersionEtag.format(version)).body(response);
                });
    }

    @PostMapping("/recovery-codes")
    public ResponseEntity<RecoveryCodesResponse> regenerateRecoveryCodes(
            Authentication authentication, @RequestHeader(IdempotencyExecutor.HEADER) String key,
            @Valid @RequestBody VerifyReasonRequest request, HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST",
                "/api/v1/auth/mfa/recovery-codes", request,
                RecoveryCodesResponse.class, () -> {
                    MfaState state = load(actor);
                    if (!state.enabled()) {
                        throw new DomainException("MFA_NOT_ENABLED", "MFA is not enabled", 409, Map.of());
                    }
                    long acceptedCounter = requireValidCode(actor, decrypt(actor, state.ciphertext()), request.code(),
                            state.lastAcceptedCounter(), servletRequest,
                            "auth.mfa_recovery_codes_failed");
                    List<String> codes = generateRecoveryCodes();
                    List<String> hashes = codes.stream().map(code -> recoveryHash(actor, code)).toList();
                    long version = jdbc.sql("""
                                    UPDATE users SET mfa_recovery_codes_json = CAST(:codes AS jsonb),
                                        mfa_last_accepted_counter = :acceptedCounter,
                                        updated_at = now(), version = version + 1
                                    WHERE tenant_id = :tenantId AND id = :id RETURNING version
                                    """)
                            .param("codes", json(hashes)).param("acceptedCounter", acceptedCounter)
                            .param("tenantId", actor.tenantId()).param("id", actor.userId())
                            .query(Long.class).single();
                    RecoveryCodesResponse response = new RecoveryCodesResponse(codes, version);
                    record(actor, "mfa.recovery_codes_regenerated", Map.of("count", codes.size()),
                            request.reason(), servletRequest);
                    return ResponseEntity.ok().eTag(VersionEtag.format(version)).body(response);
                });
    }

    @PostMapping("/disable")
    public ResponseEntity<MfaStatusResponse> disable(
            Authentication authentication, @RequestHeader(IdempotencyExecutor.HEADER) String key,
            @Valid @RequestBody VerifyReasonRequest request, HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST",
                "/api/v1/auth/mfa/disable", request,
                MfaStatusResponse.class, () -> {
                    MfaState state = load(actor);
                    if (!state.enabled()) {
                        throw new DomainException("MFA_NOT_ENABLED", "MFA is not enabled", 409, Map.of());
                    }
                    requireValidCode(actor, decrypt(actor, state.ciphertext()), request.code(),
                            state.lastAcceptedCounter(), servletRequest,
                            "auth.mfa_disable_failed");
                    long version = jdbc.sql("""
                                    UPDATE users
                                    SET mfa_enabled = false, mfa_secret_ciphertext = NULL,
                                        mfa_recovery_codes_json = '[]'::jsonb,
                                        mfa_last_accepted_counter = -1,
                                        updated_at = now(), version = version + 1
                                    WHERE tenant_id = :tenantId AND id = :id RETURNING version
                                    """)
                            .param("tenantId", actor.tenantId()).param("id", actor.userId())
                            .query(Long.class).single();
                    MfaStatusResponse response = new MfaStatusResponse(false, version);
                    record(actor, "mfa.disabled", response, request.reason(), servletRequest);
                    sessionSecurityService.refresh(actor, false, servletRequest);
                    return ResponseEntity.ok().eTag(VersionEtag.format(version)).body(response);
                });
    }

    private MfaState load(AuthenticatedUser actor) {
        return jdbc.sql("""
                        SELECT mfa_enabled, mfa_secret_ciphertext
                             , version, mfa_last_accepted_counter
                        FROM users WHERE tenant_id = :tenantId AND id = :id FOR UPDATE
                        """)
                .param("tenantId", actor.tenantId()).param("id", actor.userId())
                .query((rs, row) -> new MfaState(rs.getBoolean("mfa_enabled"),
                        rs.getString("mfa_secret_ciphertext"), rs.getLong("version"),
                        rs.getLong("mfa_last_accepted_counter"))).single();
    }

    private String decrypt(AuthenticatedUser actor, String ciphertext) {
        if (ciphertext == null) {
            throw new DomainException("MFA_ENROLLMENT_REQUIRED", "Start MFA enrollment before confirmation", 409,
                    Map.of());
        }
        return secretCipher.decrypt(ciphertext, actor.tenantId(), "user-mfa:" + actor.userId());
    }

    private long requireValidCode(AuthenticatedUser actor, String secret, String code, long lastAcceptedCounter,
                                  HttpServletRequest request, String failureAction) {
        AuthenticationThrottleService.MfaAttempt attempt = throttleService.mfaAttempt(
                actor.tenantId(), actor.userId(), request);
        if (!throttleService.mfaAllowed(attempt, request)) {
            throw throttleService.throttled();
        }
        OptionalLong acceptedCounter = totpService.matchCounter(secret, code, lastAcceptedCounter);
        if (acceptedCounter.isEmpty()) {
            throttleService.recordMfaFailure(attempt, failureAction, false, request);
            throw new DomainException("MFA_CODE_INVALID", "MFA verification code is invalid", 422, Map.of());
        }
        throttleService.recordMfaSuccess(attempt);
        return acceptedCounter.getAsLong();
    }

    private String sessionId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new DomainException("MFA_ENROLLMENT_PROOF_INVALID",
                    "MFA enrollment authorization is invalid or expired", 401, Map.of());
        }
        return session.getId();
    }

    private DomainException currentPasswordInvalid() {
        return new DomainException("CURRENT_PASSWORD_INVALID", "Current password is invalid", 401, Map.of());
    }

    List<String> generateRecoveryCodes() {
        List<String> codes = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            StringBuilder rawCode = new StringBuilder(12);
            for (int character = 0; character < 12; character++) {
                rawCode.append(BASE32.charAt(random.nextInt(BASE32.length())));
            }
            String raw = rawCode.toString();
            codes.add(raw.substring(0, 4) + "-" + raw.substring(4, 8) + "-" + raw.substring(8, 12));
        }
        return List.copyOf(codes);
    }

    private String generateBase32Secret(int byteCount) {
        byte[] bytes = new byte[byteCount];
        random.nextBytes(bytes);
        StringBuilder result = new StringBuilder((byteCount * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                result.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            result.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return result.toString();
    }

    private String recoveryHash(AuthenticatedUser actor, String code) {
        try {
            String material = actor.tenantId() + ":" + actor.userId() + ":" + code.replace("-", "").toUpperCase();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("MFA recovery codes cannot be serialized", exception);
        }
    }

    private String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private AuthenticatedUser principal(Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    private void record(AuthenticatedUser actor, String action, Object after, String reason,
                        HttpServletRequest request) {
        audit.record(actor.tenantId(), "USER", actor.userId(), actor.displayName(), action,
                "user", actor.userId(), null, after, reason, request.getHeader("X-Request-Id"));
    }

    public record EnrollmentRequest(@NotBlank @Size(max = 200) String currentPassword,
                                    @NotBlank @Size(max = 1000) String reason) {
    }

    public record ConfirmEnrollmentRequest(
            @NotBlank @Pattern(regexp = "\\d{6}") String code,
            @NotBlank @Size(min = 40, max = 128)
            @Pattern(regexp = "[A-Za-z0-9_-]+") String enrollmentProof,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record VerifyReasonRequest(@NotBlank @Pattern(regexp = "\\d{6}") String code,
                                      @NotBlank @Size(max = 1000) String reason) {
    }

    public record EnrollmentResponse(String secret, String otpauthUri, String enrollmentProof, long version) {
    }

    public record RecoveryCodesResponse(List<String> recoveryCodes, long version) {
    }

    public record MfaStatusResponse(boolean enabled, long version) {
    }

    private record EnrollmentState(String passwordHash, boolean mfaEnabled) {
    }

    private record MfaState(boolean enabled, String ciphertext, long version, long lastAcceptedCounter) {
    }
}
