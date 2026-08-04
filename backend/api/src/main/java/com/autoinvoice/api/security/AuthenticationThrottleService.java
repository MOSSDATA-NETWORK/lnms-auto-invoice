package com.autoinvoice.api.security;

import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthenticationThrottleService {
    private static final int WINDOW_SECONDS = 15 * 60;
    private static final int BLOCK_SECONDS = 15 * 60;
    private static final int LOGIN_IDENTITY_LIMIT = 5;
    private static final int LOGIN_IP_LIMIT = 20;
    private static final int MFA_USER_LIMIT = 5;
    private static final int MFA_IP_LIMIT = 20;

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final byte[] bucketKey;

    @Autowired
    public AuthenticationThrottleService(
            JdbcClient jdbc, AuditService audit,
            @Value("${auto-invoice.security.master-key-base64:}") String masterKeyBase64) {
        this(jdbc, audit, deriveBucketKey(masterKeyBase64));
    }

    AuthenticationThrottleService(JdbcClient jdbc, AuditService audit, byte[] bucketKey) {
        if (bucketKey == null || bucketKey.length != 32) {
            throw new IllegalArgumentException("Authentication throttle bucket key must contain exactly 32 bytes");
        }
        this.jdbc = jdbc;
        this.audit = audit;
        this.bucketKey = bucketKey.clone();
    }

    public LoginAttempt loginAttempt(String tenantCode, String username, HttpServletRequest request) {
        Identity identity = jdbc.sql("""
                        SELECT tenant.id AS tenant_id, app_user.id AS user_id,
                               app_user.display_name AS display_name
                        FROM tenants tenant
                        LEFT JOIN users app_user
                          ON app_user.tenant_id = tenant.id
                         AND (lower(app_user.username) = lower(:username)
                              OR lower(app_user.email) = lower(:username))
                        WHERE lower(tenant.tenant_code) = lower(:tenantCode)
                          AND tenant.status = 'ACTIVE'
                        ORDER BY CASE WHEN lower(app_user.username) = lower(:username) THEN 0 ELSE 1 END
                        LIMIT 1
                        """)
                .param("tenantCode", tenantCode)
                .param("username", username)
                .query((rs, row) -> new Identity(rs.getObject("tenant_id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getString("display_name")))
                .optional()
                .orElse(new Identity(null, null, null));
        String normalizedIdentity = tenantCode.trim().toLowerCase(Locale.ROOT) + "\n"
                + username.trim().toLowerCase(Locale.ROOT);
        return new LoginAttempt(identity, bucketHash("login-identity:" + normalizedIdentity),
                bucketHash("login-ip:" + remoteAddress(request)));
    }

    public MfaAttempt mfaAttempt(UUID tenantId, UUID userId, HttpServletRequest request) {
        Identity identity = jdbc.sql("""
                        SELECT tenant_id, id AS user_id, display_name
                        FROM users WHERE tenant_id = :tenantId AND id = :userId
                        """)
                .param("tenantId", tenantId)
                .param("userId", userId)
                .query((rs, row) -> new Identity(rs.getObject("tenant_id", UUID.class),
                        rs.getObject("user_id", UUID.class), rs.getString("display_name")))
                .optional()
                .orElse(new Identity(tenantId, userId, null));
        return new MfaAttempt(identity, bucketHash("mfa-user:" + tenantId + ":" + userId),
                bucketHash("mfa-ip:" + remoteAddress(request)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserveLoginAttempt(LoginAttempt attempt, HttpServletRequest request) {
        RateState ip = increment("LOGIN_IP", attempt.ipBucket(), new Identity(null, null, null), LOGIN_IP_LIMIT);
        if (!ip.allowed(LOGIN_IP_LIMIT)) {
            recordAudit(attempt.identity(), "auth.sign_in_blocked", "RATE_LIMITED", 0, true, request);
            return false;
        }
        RateState identity = increment("LOGIN_IDENTITY", attempt.identityBucket(), attempt.identity(),
                LOGIN_IDENTITY_LIMIT);
        if (!identity.allowed(LOGIN_IDENTITY_LIMIT)) {
            recordAudit(attempt.identity(), "auth.sign_in_blocked", "RATE_LIMITED",
                    Math.max(identity.failureCount(), ip.failureCount()), true, request);
            return false;
        }
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLoginFailure(LoginAttempt attempt, String reasonCode, HttpServletRequest request) {
        RateState identity = state("LOGIN_IDENTITY", attempt.identityBucket());
        RateState ip = state("LOGIN_IP", attempt.ipBucket());
        recordAudit(attempt.identity(), "auth.sign_in_failed", reasonCode,
                Math.max(identity.failureCount(), ip.failureCount()),
                identity.blocked() || ip.blocked(), request);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLoginSuccess(LoginAttempt attempt) {
        clear("LOGIN_IDENTITY", attempt.identityBucket());
        release("LOGIN_IP", attempt.ipBucket(), LOGIN_IP_LIMIT);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean mfaAllowed(MfaAttempt attempt, HttpServletRequest request) {
        if (isBlocked("MFA_USER", attempt.userBucket()) || isBlocked("MFA_IP", attempt.ipBucket())) {
            recordAudit(attempt.identity(), "auth.mfa_verification_blocked", "RATE_LIMITED", 0, true, request);
            return false;
        }
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordMfaFailure(MfaAttempt attempt, String action, boolean challengeRevoked,
                                 HttpServletRequest request) {
        RateState user = increment("MFA_USER", attempt.userBucket(), attempt.identity(), MFA_USER_LIMIT);
        RateState ip = increment("MFA_IP", attempt.ipBucket(), new Identity(null, null, null), MFA_IP_LIMIT);
        recordAudit(attempt.identity(), action, "INVALID_CODE",
                Math.max(user.failureCount(), ip.failureCount()),
                challengeRevoked || user.blocked() || ip.blocked(), request);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordMfaSuccess(MfaAttempt attempt) {
        clear("MFA_USER", attempt.userBucket());
    }

    private boolean isBlocked(String type, String key) {
        return jdbc.sql("""
                        SELECT blocked_until IS NOT NULL AND blocked_until > now()
                        FROM authentication_rate_limits
                        WHERE bucket_type = :type AND bucket_key_hash = :key
                        """)
                .param("type", type)
                .param("key", key)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    private RateState state(String type, String key) {
        return jdbc.sql("""
                        SELECT failure_count, blocked_until
                        FROM authentication_rate_limits
                        WHERE bucket_type = :type AND bucket_key_hash = :key
                        """)
                .param("type", type)
                .param("key", key)
                .query((rs, row) -> new RateState(rs.getInt("failure_count"),
                        rs.getObject("blocked_until", OffsetDateTime.class) != null))
                .optional()
                .orElse(new RateState(0, false));
    }

    private RateState increment(String type, String key, Identity identity, int limit) {
        return jdbc.sql("""
                        INSERT INTO authentication_rate_limits(
                            bucket_type, bucket_key_hash, tenant_id, user_id,
                            failure_count, window_started_at, blocked_until, updated_at
                        ) VALUES (
                            :type, :key, :tenantId, :userId, 1, now(),
                            CASE WHEN 1 >= :attemptLimit
                                 THEN now() + (:blockSeconds * interval '1 second') ELSE NULL END,
                            now()
                        )
                        ON CONFLICT (bucket_type, bucket_key_hash) DO UPDATE SET
                            tenant_id = COALESCE(EXCLUDED.tenant_id, authentication_rate_limits.tenant_id),
                            user_id = COALESCE(EXCLUDED.user_id, authentication_rate_limits.user_id),
                            failure_count = CASE
                                WHEN authentication_rate_limits.window_started_at
                                         <= now() - (:windowSeconds * interval '1 second')
                                     OR (authentication_rate_limits.blocked_until IS NOT NULL
                                         AND authentication_rate_limits.blocked_until <= now())
                                    THEN 1
                                ELSE authentication_rate_limits.failure_count + 1
                            END,
                            window_started_at = CASE
                                WHEN authentication_rate_limits.window_started_at
                                         <= now() - (:windowSeconds * interval '1 second')
                                     OR (authentication_rate_limits.blocked_until IS NOT NULL
                                         AND authentication_rate_limits.blocked_until <= now())
                                    THEN now()
                                ELSE authentication_rate_limits.window_started_at
                            END,
                            blocked_until = CASE
                                WHEN authentication_rate_limits.blocked_until > now()
                                    THEN authentication_rate_limits.blocked_until
                                WHEN (CASE
                                    WHEN authentication_rate_limits.window_started_at
                                             <= now() - (:windowSeconds * interval '1 second')
                                         OR (authentication_rate_limits.blocked_until IS NOT NULL
                                             AND authentication_rate_limits.blocked_until <= now())
                                        THEN 1
                                    ELSE authentication_rate_limits.failure_count + 1
                                END) >= :attemptLimit
                                    THEN now() + (:blockSeconds * interval '1 second')
                                ELSE NULL
                            END,
                            updated_at = now()
                        RETURNING failure_count, blocked_until
                        """)
                .param("type", type)
                .param("key", key)
                .param("tenantId", identity.tenantId())
                .param("userId", identity.userId())
                .param("attemptLimit", limit)
                .param("windowSeconds", WINDOW_SECONDS)
                .param("blockSeconds", BLOCK_SECONDS)
                .query((rs, row) -> new RateState(rs.getInt("failure_count"),
                        rs.getObject("blocked_until", OffsetDateTime.class) != null))
                .single();
    }

    private void clear(String type, String key) {
        jdbc.sql("""
                        DELETE FROM authentication_rate_limits
                        WHERE bucket_type = :type AND bucket_key_hash = :key
                        """)
                .param("type", type)
                .param("key", key)
                .update();
    }

    private void release(String type, String key, int limit) {
        jdbc.sql("""
                        UPDATE authentication_rate_limits
                        SET failure_count = GREATEST(0, failure_count - 1),
                            blocked_until = CASE WHEN failure_count - 1 < :attemptLimit
                                                 THEN NULL ELSE blocked_until END,
                            updated_at = now()
                        WHERE bucket_type = :type AND bucket_key_hash = :key
                        """)
                .param("attemptLimit", limit)
                .param("type", type)
                .param("key", key)
                .update();
        jdbc.sql("""
                        DELETE FROM authentication_rate_limits
                        WHERE bucket_type = :type AND bucket_key_hash = :key AND failure_count = 0
                        """)
                .param("type", type)
                .param("key", key)
                .update();
    }

    private void recordAudit(Identity identity, String action, String reasonCode,
                             int failureCount, boolean blocked, HttpServletRequest request) {
        if (identity.tenantId() == null) {
            return;
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("reason_code", reasonCode);
        after.put("failure_count", failureCount);
        after.put("blocked", blocked);
        audit.record(identity.tenantId(), "SYSTEM", null, "authentication", action,
                "authentication", identity.userId(), null, Map.copyOf(after),
                "authentication failure", request.getHeader("X-Request-Id"));
    }

    public DomainException throttled() {
        return new DomainException("AUTHENTICATION_THROTTLED",
                "Too many authentication attempts. Try again later", 429, Map.of());
    }

    String bucketHash(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(bucketKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash an authentication throttle bucket", exception);
        }
    }

    static byte[] deriveBucketKey(String encodedMasterKey) {
        if (encodedMasterKey == null || encodedMasterKey.isBlank()) {
            throw new IllegalStateException("AUTO_INVOICE_MASTER_KEY_BASE64 must be configured");
        }
        final byte[] masterKey;
        try {
            masterKey = Base64.getDecoder().decode(encodedMasterKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("AUTO_INVOICE_MASTER_KEY_BASE64 is not valid Base64", exception);
        }
        if (masterKey.length != 32) {
            throw new IllegalStateException("AUTO_INVOICE_MASTER_KEY_BASE64 must decode to exactly 32 bytes");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterKey, "HmacSHA256"));
            return mac.doFinal("auto-invoice/authentication-rate-limit/v1"
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to derive the authentication throttle bucket key", exception);
        }
    }

    private static String remoteAddress(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null || address.isBlank() ? "unknown" : address.trim().toLowerCase(Locale.ROOT);
    }

    public record LoginAttempt(Identity identity, String identityBucket, String ipBucket) {
    }

    public record MfaAttempt(Identity identity, String userBucket, String ipBucket) {
    }

    public record Identity(UUID tenantId, UUID userId, String displayName) {
    }

    private record RateState(int failureCount, boolean blocked) {
        boolean allowed(int limit) {
            return failureCount <= limit;
        }
    }
}
