package com.autoinvoice.platform.jobs;

import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Arrays;
import java.util.UUID;

@Service
public class BackgroundJobService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public BackgroundJobService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UUID enqueue(UUID tenantId, String type, String uniqueKey, JsonNode payload) {
        UUID id = UuidV7.generate();
        try {
            int inserted = jdbc.sql("""
                            INSERT INTO background_jobs(
                                id, tenant_id, job_type, unique_key, payload_json, status, available_at
                            ) VALUES (:id, :tenantId, :type, :uniqueKey, CAST(:payload AS jsonb), 'PENDING', now())
                            ON CONFLICT DO NOTHING
                            """)
                    .param("id", id)
                    .param("tenantId", tenantId)
                    .param("type", type)
                    .param("uniqueKey", uniqueKey)
                    .param("payload", objectMapper.writeValueAsString(payload))
                    .update();
            if (inserted == 1) {
                return id;
            }
            return jdbc.sql("""
                            SELECT id FROM background_jobs
                            WHERE tenant_id = :tenantId AND job_type = :type AND unique_key = :uniqueKey
                              AND status NOT IN ('DEAD', 'CANCELLED')
                            """)
                    .param("tenantId", tenantId)
                    .param("type", type)
                    .param("uniqueKey", uniqueKey)
                    .query(UUID.class)
                    .single();
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Job payload is not serializable", exception);
        }
    }

    @Transactional
    public Optional<BackgroundJob> claimNext(String workerId, Duration leaseDuration, String... jobTypes) {
        if (jobTypes.length == 0) {
            return Optional.empty();
        }
        jdbc.sql("""
                        UPDATE background_jobs
                        SET status = 'DEAD', last_error_code = 'ATTEMPT_LIMIT_EXHAUSTED',
                            last_error_message = 'Attempt limit reached while the job was leased; the worker likely crashed',
                            leased_by = NULL, leased_until = NULL, updated_at = now()
                        WHERE status = 'LEASED' AND leased_until < now() AND attempt_count >= max_attempts
                        """)
                .update();
        return jdbc.sql("""
                        WITH candidate AS (
                            SELECT id
                            FROM background_jobs
                            WHERE (
                                    (status IN ('PENDING', 'RETRY') AND available_at <= now())
                                    OR (status = 'LEASED' AND leased_until < now())
                                  )
                              AND attempt_count < max_attempts
                              AND job_type IN (:types)
                            ORDER BY priority DESC, created_at
                            FOR UPDATE SKIP LOCKED
                            LIMIT 1
                        )
                        UPDATE background_jobs job
                        SET status = 'LEASED', leased_by = :workerId,
                            leased_until = now() + (:leaseSeconds * interval '1 second'),
                            attempt_count = attempt_count + 1, updated_at = now()
                        FROM candidate
                        WHERE job.id = candidate.id
                        RETURNING job.*
                        """)
                .param("types", Arrays.asList(jobTypes))
                .param("workerId", workerId)
                .param("leaseSeconds", leaseDuration.toSeconds())
                .query(this::mapJob)
                .optional();
    }

    @Transactional
    public void complete(UUID jobId, String workerId, JsonNode result) {
        updateLeasedJob(jobId, workerId, """
                status = 'COMPLETED', result_json = CAST(:result AS jsonb),
                completed_at = now(), leased_by = NULL, leased_until = NULL, updated_at = now()
                """, result == null ? "null" : result.toString(), null, null);
    }

    @Transactional
    public void renewLease(UUID jobId, String workerId, Duration leaseDuration) {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Job lease duration must be positive");
        }
        int updated = jdbc.sql("""
                        UPDATE background_jobs
                        SET leased_until = now() + (:leaseMillis * interval '1 millisecond'), updated_at = now()
                        WHERE id = :jobId AND status = 'LEASED' AND leased_by = :workerId
                          AND leased_until > now()
                        """)
                .param("leaseMillis", leaseDuration.toMillis())
                .param("jobId", jobId)
                .param("workerId", workerId)
                .update();
        if (updated != 1) {
            throw new DomainException("JOB_LEASE_LOST", "The job lease is no longer owned by this worker", 409,
                    java.util.Map.of("job_id", jobId));
        }
    }

    @Transactional
    public void fail(UUID jobId, String workerId, String errorCode, String errorMessage, Duration retryAfter) {
        updateLeasedJob(jobId, workerId, """
                status = CASE WHEN attempt_count >= max_attempts THEN 'DEAD' ELSE 'RETRY' END,
                last_error_code = :errorCode, last_error_message = :errorMessage,
                available_at = now() + (:retrySeconds * interval '1 second'),
                leased_by = NULL, leased_until = NULL, updated_at = now()
                """, null, errorCode, errorMessage, retryAfter.toSeconds());
    }

    private void updateLeasedJob(UUID jobId, String workerId, String updateClause,
                                 String result, String errorCode, String errorMessage, long... retrySeconds) {
        JdbcClient.StatementSpec statement = jdbc.sql("""
                        UPDATE background_jobs SET %s
                        WHERE id = :jobId AND status = 'LEASED' AND leased_by = :workerId
                          AND leased_until > now()
                        """.formatted(updateClause))
                .param("jobId", jobId)
                .param("workerId", workerId);
        if (result != null) {
            statement = statement.param("result", result);
        }
        if (errorCode != null) {
            statement = statement.param("errorCode", errorCode)
                    .param("errorMessage", errorMessage)
                    .param("retrySeconds", retrySeconds[0]);
        }
        if (statement.update() != 1) {
            throw new DomainException("JOB_LEASE_LOST", "The job lease is no longer owned by this worker", 409,
                    java.util.Map.of("job_id", jobId));
        }
    }

    private BackgroundJob mapJob(ResultSet rs, int rowNum) throws SQLException {
        try {
            OffsetDateTime leasedUntil = rs.getObject("leased_until", OffsetDateTime.class);
            return new BackgroundJob(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getString("job_type"),
                    rs.getString("unique_key"),
                    objectMapper.readTree(rs.getString("payload_json")),
                    rs.getString("status"),
                    rs.getInt("attempt_count"),
                    rs.getInt("max_attempts"),
                    rs.getObject("available_at", OffsetDateTime.class).toInstant(),
                    leasedUntil == null ? null : leasedUntil.withOffsetSameInstant(ZoneOffset.UTC).toInstant()
            );
        } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid persisted job JSON", exception);
        }
    }
}
