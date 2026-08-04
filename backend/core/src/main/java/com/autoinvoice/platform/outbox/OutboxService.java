package com.autoinvoice.platform.outbox;

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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class OutboxService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public OutboxService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public UUID append(UUID tenantId, String aggregateType, UUID aggregateId,
                       String eventType, int eventVersion, Object payload, Map<String, String> headers) {
        UUID eventId = UuidV7.generate();
        try {
            jdbc.sql("""
                            INSERT INTO outbox_events(
                                id, tenant_id, aggregate_type, aggregate_id, event_type, event_version,
                                payload_json, headers_json, status, occurred_at
                            ) VALUES (
                                :id, :tenantId, :aggregateType, :aggregateId, :eventType, :eventVersion,
                                CAST(:payload AS jsonb), CAST(:headers AS jsonb), 'PENDING', :occurredAt
                            )
                            """)
                    .param("id", eventId)
                    .param("tenantId", tenantId)
                    .param("aggregateType", aggregateType)
                    .param("aggregateId", aggregateId)
                    .param("eventType", eventType)
                    .param("eventVersion", eventVersion)
                    .param("payload", objectMapper.writeValueAsString(payload))
                    .param("headers", objectMapper.writeValueAsString(headers))
                    .param("occurredAt", OffsetDateTime.now(ZoneOffset.UTC))
                    .update();
            return eventId;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Outbox payload is not serializable", exception);
        }
    }

    @Transactional
    public Optional<OutboxEvent> claimNext(String publisherId, Duration leaseDuration) {
        return jdbc.sql("""
                        WITH candidate AS (
                            SELECT id FROM outbox_events
                            WHERE ((status IN ('PENDING', 'RETRY') AND available_at <= now())
                                   OR (status = 'PUBLISHING' AND locked_until < now()))
                            ORDER BY occurred_at, id
                            FOR UPDATE SKIP LOCKED
                            LIMIT 1
                        )
                        UPDATE outbox_events event
                        SET status = 'PUBLISHING', locked_by = :publisherId,
                            locked_until = now() + (:leaseSeconds * interval '1 second'),
                            attempt_count = attempt_count + 1
                        FROM candidate
                        WHERE event.id = candidate.id
                        RETURNING event.*
                        """)
                .param("publisherId", publisherId).param("leaseSeconds", leaseDuration.toSeconds())
                .query(this::mapEvent).optional();
    }

    @Transactional
    public void complete(UUID eventId, String publisherId) {
        int updated = jdbc.sql("""
                        UPDATE outbox_events
                        SET status = 'PUBLISHED', published_at = COALESCE(published_at, now()),
                            locked_by = NULL, locked_until = NULL, last_error = NULL
                        WHERE id = :eventId AND status = 'PUBLISHING' AND locked_by = :publisherId
                          AND locked_until > now()
                        """)
                .param("eventId", eventId).param("publisherId", publisherId).update();
        if (updated != 1) {
            throw leaseLost(eventId);
        }
    }

    @Transactional
    public void fail(UUID eventId, String publisherId, String error, Duration retryAfter) {
        int updated = jdbc.sql("""
                        UPDATE outbox_events
                        SET status = CASE WHEN attempt_count >= 10 THEN 'DEAD' ELSE 'RETRY' END,
                            available_at = now() + (:retrySeconds * interval '1 second'),
                            locked_by = NULL, locked_until = NULL, last_error = :error
                        WHERE id = :eventId AND status = 'PUBLISHING' AND locked_by = :publisherId
                          AND locked_until > now()
                        """)
                .param("retrySeconds", retryAfter.toSeconds()).param("error", truncate(error, 4000))
                .param("eventId", eventId).param("publisherId", publisherId).update();
        if (updated != 1) {
            throw leaseLost(eventId);
        }
    }

    private OutboxEvent mapEvent(ResultSet rs, int row) throws SQLException {
        try {
            OffsetDateTime lockedUntil = rs.getObject("locked_until", OffsetDateTime.class);
            return new OutboxEvent(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                    rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class),
                    rs.getString("event_type"), rs.getInt("event_version"),
                    objectMapper.readTree(rs.getString("payload_json")),
                    objectMapper.readTree(rs.getString("headers_json")), rs.getInt("attempt_count"),
                    rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                    lockedUntil == null ? null : lockedUntil.withOffsetSameInstant(ZoneOffset.UTC).toInstant());
        } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid persisted outbox JSON", exception);
        }
    }

    private IllegalStateException leaseLost(UUID eventId) {
        return new IllegalStateException("Outbox event lease was lost: " + eventId);
    }

    private String truncate(String value, int maximum) {
        String normalized = value == null ? "Outbox publication failed" : value;
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    public record OutboxEvent(UUID id, UUID tenantId, String aggregateType, UUID aggregateId,
                              String eventType, int eventVersion, JsonNode payload, JsonNode headers,
                              int attemptCount, Instant occurredAt, Instant lockedUntil) {
    }
}
