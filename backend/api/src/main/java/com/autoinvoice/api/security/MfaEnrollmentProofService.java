package com.autoinvoice.api.security;

import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class MfaEnrollmentProofService {
    private final JdbcClient jdbc;
    private final SecureRandom random;

    @Autowired
    public MfaEnrollmentProofService(JdbcClient jdbc) {
        this(jdbc, new SecureRandom());
    }

    MfaEnrollmentProofService(JdbcClient jdbc, SecureRandom random) {
        this.jdbc = jdbc;
        this.random = random;
    }

    public String issue(AuthenticatedUser actor, String sessionId, long secretVersion) {
        requireSession(sessionId);
        jdbc.sql("""
                        UPDATE mfa_enrollment_proofs
                        SET revoked_at = now(), updated_at = now()
                        WHERE tenant_id = :tenantId AND user_id = :userId
                          AND consumed_at IS NULL AND revoked_at IS NULL
                        """)
                .param("tenantId", actor.tenantId())
                .param("userId", actor.userId())
                .update();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.sql("""
                        INSERT INTO mfa_enrollment_proofs(
                            id, tenant_id, user_id, proof_hash, session_binding_hash,
                            secret_version, expires_at
                        ) VALUES (
                            :id, :tenantId, :userId, :proofHash, :sessionHash,
                            :secretVersion, now() + interval '5 minutes'
                        )
                        """)
                .param("id", UuidV7.generate())
                .param("tenantId", actor.tenantId())
                .param("userId", actor.userId())
                .param("proofHash", proofHash(token))
                .param("sessionHash", sessionHash(sessionId))
                .param("secretVersion", secretVersion)
                .update();
        return token;
    }

    public UUID requireValid(AuthenticatedUser actor, String sessionId, String token, long secretVersion) {
        requireSession(sessionId);
        if (token == null || token.isBlank()) {
            throw invalid();
        }
        Proof proof = jdbc.sql("""
                        SELECT id, session_binding_hash, secret_version,
                               expires_at > clock_timestamp() AS unexpired,
                               consumed_at, revoked_at
                        FROM mfa_enrollment_proofs
                        WHERE tenant_id = :tenantId AND user_id = :userId
                          AND proof_hash = :proofHash
                        FOR UPDATE
                        """)
                .param("tenantId", actor.tenantId())
                .param("userId", actor.userId())
                .param("proofHash", proofHash(token))
                .query((rs, row) -> new Proof(
                        rs.getObject("id", UUID.class),
                        rs.getString("session_binding_hash"),
                        rs.getLong("secret_version"),
                        rs.getBoolean("unexpired"),
                        rs.getObject("consumed_at") != null,
                        rs.getObject("revoked_at") != null))
                .optional()
                .orElseThrow(this::invalid);
        if (!proof.unexpired() || proof.consumed() || proof.revoked()
                || proof.secretVersion() != secretVersion
                || !constantEquals(proof.sessionHash(), sessionHash(sessionId))) {
            throw invalid();
        }
        return proof.id();
    }

    public void consume(AuthenticatedUser actor, UUID proofId) {
        int consumed = jdbc.sql("""
                        UPDATE mfa_enrollment_proofs
                        SET consumed_at = now(), updated_at = now()
                        WHERE tenant_id = :tenantId AND user_id = :userId AND id = :id
                          AND consumed_at IS NULL AND revoked_at IS NULL
                          AND expires_at > clock_timestamp()
                        """)
                .param("tenantId", actor.tenantId())
                .param("userId", actor.userId())
                .param("id", proofId)
                .update();
        if (consumed != 1) {
            throw invalid();
        }
    }

    private void requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw invalid();
        }
    }

    private DomainException invalid() {
        return new DomainException("MFA_ENROLLMENT_PROOF_INVALID",
                "MFA enrollment authorization is invalid or expired", 401, Map.of());
    }

    private static String proofHash(String token) {
        return sha256("mfa-enrollment-proof:" + token);
    }

    private static String sessionHash(String sessionId) {
        return sha256("mfa-enrollment-session:" + sessionId);
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

    private record Proof(UUID id, String sessionHash, long secretVersion,
                         boolean unexpired, boolean consumed, boolean revoked) {
    }
}
