package com.autoinvoice.api.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationArtifactCleanupService {
    static final int BATCH_SIZE = 5_000;

    private final JdbcClient jdbc;

    public AuthenticationArtifactCleanupService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${auto-invoice.security.authentication-artifact-cleanup-cron:0 17 * * * *}")
    @Transactional
    public CleanupResult cleanupExpiredArtifacts() {
        int rateLimits = jdbc.sql("""
                        WITH stale AS (
                            SELECT ctid
                            FROM authentication_rate_limits
                            WHERE updated_at < clock_timestamp() - interval '24 hours'
                              AND (blocked_until IS NULL OR blocked_until <= clock_timestamp())
                            ORDER BY updated_at
                            LIMIT :batchSize
                            FOR UPDATE SKIP LOCKED
                        )
                        DELETE FROM authentication_rate_limits target
                        USING stale
                        WHERE target.ctid = stale.ctid
                        """)
                .param("batchSize", BATCH_SIZE)
                .update();
        int challenges = jdbc.sql("""
                        WITH stale AS (
                            SELECT ctid
                            FROM mfa_login_challenges
                            WHERE updated_at < clock_timestamp() - interval '24 hours'
                              AND (expires_at <= clock_timestamp()
                                   OR consumed_at IS NOT NULL OR revoked_at IS NOT NULL)
                            ORDER BY updated_at
                            LIMIT :batchSize
                            FOR UPDATE SKIP LOCKED
                        )
                        DELETE FROM mfa_login_challenges target
                        USING stale
                        WHERE target.ctid = stale.ctid
                        """)
                .param("batchSize", BATCH_SIZE)
                .update();
        int enrollmentProofs = jdbc.sql("""
                        WITH stale AS (
                            SELECT ctid
                            FROM mfa_enrollment_proofs
                            WHERE updated_at < clock_timestamp() - interval '24 hours'
                              AND (expires_at <= clock_timestamp()
                                   OR consumed_at IS NOT NULL OR revoked_at IS NOT NULL)
                            ORDER BY updated_at
                            LIMIT :batchSize
                            FOR UPDATE SKIP LOCKED
                        )
                        DELETE FROM mfa_enrollment_proofs target
                        USING stale
                        WHERE target.ctid = stale.ctid
                        """)
                .param("batchSize", BATCH_SIZE)
                .update();
        return new CleanupResult(rateLimits, challenges, enrollmentProofs);
    }

    public record CleanupResult(int rateLimits, int challenges, int enrollmentProofs) {
    }
}
