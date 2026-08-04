package com.autoinvoice.platform.audit;

import com.autoinvoice.platform.UuidV7;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AuditServiceIntegrationTest {
    private static final int EVENT_COUNT = 24;

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.9-alpine");

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static AuditService audit;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        audit = new AuditService(jdbc, new ObjectMapper());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void recordBindsCreatedAtAsPostgreSqlTimestampWithTimeZone() {
        UUID tenantId = UuidV7.generate();
        jdbc.sql("INSERT INTO tenants(id, tenant_code, tenant_name) VALUES (:id, :code, :name)")
                .param("id", tenantId).param("code", "audit-time-" + tenantId).param("name", "Audit time test")
                .update();
        Instant earliestExpected = Instant.now().minusSeconds(1);

        transactions.executeWithoutResult(status -> audit.record(
                tenantId, "USER", UUID.fromString("01900000-0000-7000-8000-000000000001"),
                "Timestamp auditor", "test.timestamp", "test_object", UuidV7.generate(),
                null, Map.of("recorded", true), "timestamp binding", "request-timestamp"));

        Instant latestExpected = Instant.now().plusSeconds(1);
        OffsetDateTime createdAt = jdbc.sql("""
                        SELECT created_at FROM audit_logs
                        WHERE tenant_id = :tenantId AND action = 'test.timestamp'
                        """)
                .param("tenantId", tenantId)
                .query(OffsetDateTime.class)
                .single();

        assertThat(createdAt.toInstant()).isBetween(earliestExpected, latestExpected);
    }

    @Test
    void concurrentWritersProduceOneVerifiableLinearChain() throws Exception {
        UUID tenantId = UuidV7.generate();
        jdbc.sql("INSERT INTO tenants(id, tenant_code, tenant_name) VALUES (:id, :code, :name)")
                .param("id", tenantId).param("code", "audit-" + tenantId).param("name", "Audit test")
                .update();

        CountDownLatch ready = new CountDownLatch(EVENT_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < EVENT_COUNT; index++) {
                int event = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    transactions.executeWithoutResult(status -> audit.record(
                            tenantId, "USER", UUID.fromString("01900000-0000-7000-8000-000000000001"),
                            "Concurrent auditor", "test.event", "test_object", UuidV7.generate(),
                            Map.of("event", event), Map.of("result", event), "reason-" + event, "request-" + event));
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        List<AuditRow> rows = jdbc.sql("""
                        SELECT id, tenant_id, actor_type, actor_id, actor_display, action, object_type, object_id,
                               correlation_id, request_id, before_json::text, after_json::text, metadata_json::text,
                               host(ip_address), user_agent, previous_hash, event_hash, created_at
                        FROM audit_logs WHERE tenant_id = :tenantId
                        """)
                .param("tenantId", tenantId)
                .query((rs, row) -> new AuditRow(
                        rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getString("actor_type"), rs.getObject("actor_id", UUID.class),
                        rs.getString("actor_display"), rs.getString("action"), rs.getString("object_type"),
                        rs.getObject("object_id", UUID.class), rs.getString("correlation_id"),
                        rs.getString("request_id"), rs.getString("before_json"), rs.getString("after_json"),
                        rs.getString("metadata_json"), rs.getString("host"), rs.getString("user_agent"),
                        rs.getString("previous_hash"), rs.getString("event_hash"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
        assertThat(rows).hasSize(EVENT_COUNT);

        Map<String, AuditRow> successorByHash = new HashMap<>();
        AuditRow root = null;
        for (AuditRow row : rows) {
            if (row.previousHash() == null) {
                assertThat(root).isNull();
                root = row;
            } else {
                assertThat(successorByHash.put(row.previousHash(), row)).isNull();
            }
            assertHashIsVerifiable(row);
        }
        assertThat(root).isNotNull();

        int visited = 1;
        AuditRow current = root;
        while (successorByHash.containsKey(current.eventHash())) {
            current = successorByHash.get(current.eventHash());
            visited++;
        }
        assertThat(visited).isEqualTo(EVENT_COUNT);
        assertThat(jdbc.sql("SELECT last_event_hash FROM audit_chain_heads WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId).query(String.class).single()).isEqualTo(current.eventHash());
    }

    private void assertHashIsVerifiable(AuditRow row) throws Exception {
        AuditService.HashMaterial material = new AuditService.HashMaterial(
                row.previousHash(), row.id(), row.tenantId(), row.actorType(), row.actorId(), row.actorDisplay(),
                row.action(), row.objectType(), row.objectId(), row.correlationId(), row.requestId(),
                audit.canonicalizeJsonText(row.beforeJson()), audit.canonicalizeJsonText(row.afterJson()),
                audit.canonicalizeJsonText(row.metadataJson()), row.ipAddress(), row.userAgent(),
                row.createdAt().toInstant());
        assertThat(AuditService.computeEventHash(material)).isEqualTo(row.eventHash());
    }

    private record AuditRow(UUID id, UUID tenantId, String actorType, UUID actorId, String actorDisplay,
                            String action, String objectType, UUID objectId, String correlationId,
                            String requestId, String beforeJson, String afterJson, String metadataJson,
                            String ipAddress, String userAgent, String previousHash, String eventHash,
                            OffsetDateTime createdAt) {
    }
}
