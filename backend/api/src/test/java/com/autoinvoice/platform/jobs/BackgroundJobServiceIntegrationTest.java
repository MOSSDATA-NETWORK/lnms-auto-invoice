package com.autoinvoice.platform.jobs;

import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class BackgroundJobServiceIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.9-alpine");

    private static JdbcClient jdbc;
    private static BackgroundJobService jobs;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        jobs = new BackgroundJobService(jdbc, new ObjectMapper());
    }

    @Test
    void onlyTheCurrentOwnerCanRenewAnUnexpiredLease() {
        UUID tenantId = UuidV7.generate();
        jdbc.sql("INSERT INTO tenants(id, tenant_code, tenant_name) VALUES (:id, :code, :name)")
                .param("id", tenantId).param("code", "lease-" + tenantId).param("name", "Lease test")
                .update();
        UUID jobId = jobs.enqueue(tenantId, "TEST_JOB", "lease-test-" + tenantId,
                JsonNodeFactory.instance.objectNode());
        BackgroundJob claimed = jobs.claimNext("worker-1", Duration.ofMinutes(2), "TEST_JOB").orElseThrow();
        assertThat(claimed.id()).isEqualTo(jobId);

        jobs.renewLease(jobId, "worker-1", Duration.ofMinutes(5));

        assertThat(jdbc.sql("""
                        SELECT leased_until > now() + interval '4 minutes'
                        FROM background_jobs WHERE id = :id
                        """).param("id", jobId).query(Boolean.class).single()).isTrue();
        assertThatThrownBy(() -> jobs.renewLease(jobId, "worker-2", Duration.ofMinutes(5)))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).code())
                .isEqualTo("JOB_LEASE_LOST");
    }

    @Test
    void reenqueueAfterTerminalDeadCreatesAFreshJob() {
        UUID tenantId = newTenant("reenqueue-dead");
        String uniqueKey = "reenqueue-dead-" + tenantId;
        UUID firstId = jobs.enqueue(tenantId, "TEST_REENQUEUE", uniqueKey, JsonNodeFactory.instance.objectNode());
        jdbc.sql("UPDATE background_jobs SET status = 'DEAD', attempt_count = max_attempts WHERE id = :id")
                .param("id", firstId).update();

        UUID secondId = jobs.enqueue(tenantId, "TEST_REENQUEUE", uniqueKey, JsonNodeFactory.instance.objectNode());

        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(jdbc.sql("SELECT status FROM background_jobs WHERE id = :id")
                .param("id", secondId).query(String.class).single()).isEqualTo("PENDING");
    }

    @Test
    void reenqueueWhileActiveOrCompletedStillDeduplicates() {
        UUID tenantId = newTenant("reenqueue-active");
        String uniqueKey = "reenqueue-active-" + tenantId;
        UUID firstId = jobs.enqueue(tenantId, "TEST_REENQUEUE", uniqueKey, JsonNodeFactory.instance.objectNode());

        assertThat(jobs.enqueue(tenantId, "TEST_REENQUEUE", uniqueKey, JsonNodeFactory.instance.objectNode()))
                .isEqualTo(firstId);

        jdbc.sql("UPDATE background_jobs SET status = 'COMPLETED', completed_at = now() WHERE id = :id")
                .param("id", firstId).update();
        assertThat(jobs.enqueue(tenantId, "TEST_REENQUEUE", uniqueKey, JsonNodeFactory.instance.objectNode()))
                .isEqualTo(firstId);
    }

    @Test
    void claimNextSweepsCrashedJobsPastTheAttemptLimitToDead() {
        UUID tenantId = newTenant("poison");
        UUID jobId = jobs.enqueue(tenantId, "TEST_POISON", "poison-" + tenantId,
                JsonNodeFactory.instance.objectNode());
        BackgroundJob claimed = jobs.claimNext("worker-1", Duration.ofMinutes(2), "TEST_POISON").orElseThrow();
        jdbc.sql("""
                UPDATE background_jobs
                SET leased_until = now() - interval '1 minute', attempt_count = max_attempts
                WHERE id = :id
                """).param("id", claimed.id()).update();

        assertThat(jobs.claimNext("worker-2", Duration.ofMinutes(2), "TEST_POISON")).isEmpty();
        assertThat(jdbc.sql("SELECT status FROM background_jobs WHERE id = :id")
                .param("id", jobId).query(String.class).single()).isEqualTo("DEAD");
    }

    @Test
    void claimNextReclaimsAnExpiredLeaseBelowTheAttemptLimit() {
        UUID tenantId = newTenant("reclaim");
        UUID jobId = jobs.enqueue(tenantId, "TEST_RECLAIM", "reclaim-" + tenantId,
                JsonNodeFactory.instance.objectNode());
        BackgroundJob claimed = jobs.claimNext("worker-1", Duration.ofMinutes(2), "TEST_RECLAIM").orElseThrow();
        jdbc.sql("UPDATE background_jobs SET leased_until = now() - interval '1 minute' WHERE id = :id")
                .param("id", claimed.id()).update();

        BackgroundJob reclaimed = jobs.claimNext("worker-2", Duration.ofMinutes(2), "TEST_RECLAIM").orElseThrow();

        assertThat(reclaimed.id()).isEqualTo(jobId);
        assertThat(reclaimed.attemptCount()).isEqualTo(2);
    }

    private UUID newTenant(String prefix) {
        UUID tenantId = UuidV7.generate();
        jdbc.sql("INSERT INTO tenants(id, tenant_code, tenant_name) VALUES (:id, :code, :name)")
                .param("id", tenantId).param("code", prefix + "-" + tenantId).param("name", prefix)
                .update();
        return tenantId;
    }
}
