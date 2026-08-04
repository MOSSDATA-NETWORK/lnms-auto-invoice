package com.autoinvoice.api.security;

import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.security.SecretCipher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

@Service
public class MfaChallengeService {
    private static final int MAX_FAILURES = 5;
    private final JdbcClient jdbc;
    private final TotpService totpService;
    private final SecretCipher secretCipher;
    private final ObjectMapper objectMapper;
    private final DatabaseUserDetailsService userDetailsService;

    public MfaChallengeService(JdbcClient jdbc, TotpService totpService, SecretCipher secretCipher,
                               ObjectMapper objectMapper, DatabaseUserDetailsService userDetailsService) {
        this.jdbc = jdbc;
        this.totpService = totpService;
        this.secretCipher = secretCipher;
        this.objectMapper = objectMapper;
        this.userDetailsService = userDetailsService;
    }

    @Transactional
    public PendingChallenge create(AuthenticatedUser user, String sessionId) {
        jdbc.sql("""
                        UPDATE mfa_login_challenges
                        SET revoked_at = now(), updated_at = now()
                        WHERE tenant_id = :tenantId AND user_id = :userId
                          AND consumed_at IS NULL AND revoked_at IS NULL
                        """)
                .param("tenantId", user.tenantId())
                .param("userId", user.userId())
                .update();
        UUID challengeId = UuidV7.generate();
        jdbc.sql("""
                        INSERT INTO mfa_login_challenges(
                            id, tenant_id, user_id, session_binding_hash, expires_at
                        ) VALUES (
                            :id, :tenantId, :userId, :sessionHash, now() + interval '5 minutes'
                        )
                        """)
                .param("id", challengeId)
                .param("tenantId", user.tenantId())
                .param("userId", user.userId())
                .param("sessionHash", sessionHash(sessionId))
                .update();
        return new PendingChallenge(challengeId, user.tenantId(), user.userId());
    }

    @Transactional
    public Verification verify(PendingChallenge pending, String sessionId, String code) {
        Challenge challenge = jdbc.sql("""
                        SELECT session_binding_hash, failed_attempts, expires_at, consumed_at, revoked_at
                        FROM mfa_login_challenges
                        WHERE id = :id AND tenant_id = :tenantId AND user_id = :userId
                        FOR UPDATE
                        """)
                .param("id", pending.challengeId())
                .param("tenantId", pending.tenantId())
                .param("userId", pending.userId())
                .query((rs, row) -> new Challenge(rs.getString("session_binding_hash"),
                        rs.getInt("failed_attempts"), rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("consumed_at", OffsetDateTime.class),
                        rs.getObject("revoked_at", OffsetDateTime.class)))
                .optional()
                .orElse(null);
        if (challenge == null || !constantEquals(challenge.sessionHash(), sessionHash(sessionId))) {
            return Verification.invalid(true);
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (challenge.consumedAt() != null || challenge.revokedAt() != null
                || !challenge.expiresAt().isAfter(now) || challenge.failedAttempts() >= MAX_FAILURES) {
            if (challenge.consumedAt() == null && challenge.revokedAt() == null) {
                revoke(pending);
            }
            return Verification.invalid(true);
        }

        UserMfaState user = jdbc.sql("""
                        SELECT app_user.status, app_user.mfa_enabled, app_user.mfa_secret_ciphertext,
                               app_user.mfa_last_accepted_counter,
                               mfa_recovery_codes_json::text AS recovery_codes
                        FROM users app_user
                        JOIN tenants tenant ON tenant.id = app_user.tenant_id
                        WHERE app_user.tenant_id = :tenantId AND app_user.id = :userId
                          AND tenant.status = 'ACTIVE'
                        FOR UPDATE OF app_user
                        """)
                .param("tenantId", pending.tenantId())
                .param("userId", pending.userId())
                .query((rs, row) -> new UserMfaState(rs.getString("status"), rs.getBoolean("mfa_enabled"),
                        rs.getString("mfa_secret_ciphertext"), rs.getLong("mfa_last_accepted_counter"),
                        rs.getString("recovery_codes")))
                .optional()
                .orElse(null);
        if (user == null || !"ACTIVE".equals(user.status()) || !user.mfaEnabled()
                || user.ciphertext() == null) {
            revoke(pending);
            return Verification.invalid(true);
        }

        String secret = secretCipher.decrypt(user.ciphertext(), pending.tenantId(),
                "user-mfa:" + pending.userId());
        OptionalLong acceptedCounter = totpService.matchCounter(secret, code, user.lastAcceptedCounter());
        boolean validTotp = acceptedCounter.isPresent();
        boolean valid = validTotp || consumeRecoveryCode(pending, user.recoveryCodes(), code);
        if (!valid) {
            Failure failure = jdbc.sql("""
                            UPDATE mfa_login_challenges
                            SET failed_attempts = LEAST(:maxFailures, failed_attempts + 1),
                                revoked_at = CASE WHEN failed_attempts + 1 >= :maxFailures
                                                  THEN now() ELSE revoked_at END,
                                updated_at = now()
                            WHERE tenant_id = :tenantId AND user_id = :userId AND id = :id
                            RETURNING failed_attempts, revoked_at
                            """)
                    .param("maxFailures", MAX_FAILURES)
                    .param("tenantId", pending.tenantId())
                    .param("userId", pending.userId())
                    .param("id", pending.challengeId())
                    .query((rs, row) -> new Failure(rs.getInt("failed_attempts"),
                            rs.getObject("revoked_at", OffsetDateTime.class) != null))
                    .single();
            return Verification.invalid(failure.revoked());
        }

        if (validTotp) {
            jdbc.sql("""
                            UPDATE users
                            SET mfa_last_accepted_counter = :acceptedCounter, updated_at = now()
                            WHERE tenant_id = :tenantId AND id = :userId
                            """)
                    .param("acceptedCounter", acceptedCounter.getAsLong())
                    .param("tenantId", pending.tenantId())
                    .param("userId", pending.userId())
                    .update();
        }

        jdbc.sql("""
                        UPDATE mfa_login_challenges
                        SET consumed_at = now(), updated_at = now()
                        WHERE tenant_id = :tenantId AND user_id = :userId AND id = :id
                          AND consumed_at IS NULL AND revoked_at IS NULL
                        """)
                .param("tenantId", pending.tenantId())
                .param("userId", pending.userId())
                .param("id", pending.challengeId())
                .update();
        AuthenticatedUser current = userDetailsService.findCurrent(pending.tenantId(), pending.userId())
                .filter(AuthenticatedUser::enabled)
                .orElse(null);
        if (current == null) {
            return Verification.invalid(true);
        }
        return Verification.verified(current);
    }

    private boolean consumeRecoveryCode(PendingChallenge pending, String storedJson, String candidate) {
        String normalized = candidate == null ? "" : candidate.replace("-", "").trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9_]{12}")) {
            return false;
        }
        try {
            List<String> hashes = new ArrayList<>(objectMapper.readValue(storedJson, new TypeReference<>() {}));
            String candidateHash = recoveryHash(pending.tenantId(), pending.userId(), normalized);
            int match = -1;
            for (int index = 0; index < hashes.size(); index++) {
                if (constantEquals(candidateHash, hashes.get(index))) {
                    match = index;
                    break;
                }
            }
            if (match < 0) {
                return false;
            }
            hashes.remove(match);
            jdbc.sql("""
                            UPDATE users
                            SET mfa_recovery_codes_json = CAST(:codes AS jsonb), updated_at = now()
                            WHERE tenant_id = :tenantId AND id = :userId
                            """)
                    .param("codes", objectMapper.writeValueAsString(hashes))
                    .param("tenantId", pending.tenantId())
                    .param("userId", pending.userId())
                    .update();
            return true;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted MFA recovery codes are invalid", exception);
        }
    }

    private void revoke(PendingChallenge pending) {
        jdbc.sql("""
                        UPDATE mfa_login_challenges
                        SET revoked_at = COALESCE(revoked_at, now()), updated_at = now()
                        WHERE tenant_id = :tenantId AND user_id = :userId AND id = :id
                          AND consumed_at IS NULL
                        """)
                .param("tenantId", pending.tenantId())
                .param("userId", pending.userId())
                .param("id", pending.challengeId())
                .update();
    }

    private static String recoveryHash(UUID tenantId, UUID userId, String normalizedCode) {
        return sha256(tenantId + ":" + userId + ":" + normalizedCode);
    }

    private static String sessionHash(String sessionId) {
        return sha256("mfa-session:" + sessionId);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean constantEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    public record PendingChallenge(UUID challengeId, UUID tenantId, UUID userId) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    public record Verification(boolean verified, boolean challengeRevoked, AuthenticatedUser user) {
        static Verification verified(AuthenticatedUser user) {
            return new Verification(true, false, user);
        }

        static Verification invalid(boolean revoked) {
            return new Verification(false, revoked, null);
        }
    }

    private record Challenge(String sessionHash, int failedAttempts, OffsetDateTime expiresAt,
                             OffsetDateTime consumedAt, OffsetDateTime revokedAt) {
    }

    private record UserMfaState(String status, boolean mfaEnabled, String ciphertext,
                                long lastAcceptedCounter, String recoveryCodes) {
    }

    private record Failure(int failedAttempts, boolean revoked) {
    }
}
