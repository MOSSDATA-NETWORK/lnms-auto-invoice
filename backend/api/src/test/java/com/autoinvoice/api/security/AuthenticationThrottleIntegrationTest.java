package com.autoinvoice.api.security;

import com.autoinvoice.platform.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class AuthenticationThrottleIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.9-alpine");

    private static JdbcClient jdbc;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
    }

    @Test
    void atomicallyAllowsOnlyTwentyConcurrentReservationsForOneIp() throws Exception {
        AuthenticationThrottleService throttle = service();
        String ipBucket = throttle.bucketHash("concurrent-ip:" + UUID.randomUUID());
        CountDownLatch ready = new CountDownLatch(25);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(25);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int index = 0; index < 25; index++) {
                String identityBucket = throttle.bucketHash("identity:" + UUID.randomUUID());
                AuthenticationThrottleService.LoginAttempt attempt = attempt(identityBucket, ipBucket);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return throttle.reserveLoginAttempt(attempt, mock(HttpServletRequest.class));
                }));
            }
            ready.await();
            start.countDown();

            int allowed = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(20);
            assertThat(jdbc.sql("""
                            SELECT failure_count FROM authentication_rate_limits
                            WHERE bucket_type = 'LOGIN_IP' AND bucket_key_hash = :key
                            """).param("key", ipBucket).query(Integer.class).single()).isEqualTo(25);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void successReleasesOnlyItsOwnIpReservationAndFailureIsNotCountedTwice() {
        AuthenticationThrottleService throttle = service();
        String ipBucket = throttle.bucketHash("shared-ip:" + UUID.randomUUID());
        AuthenticationThrottleService.LoginAttempt failed = attempt(
                throttle.bucketHash("failed-identity:" + UUID.randomUUID()), ipBucket);
        AuthenticationThrottleService.LoginAttempt successful = attempt(
                throttle.bucketHash("successful-identity:" + UUID.randomUUID()), ipBucket);
        HttpServletRequest request = mock(HttpServletRequest.class);

        assertThat(throttle.reserveLoginAttempt(failed, request)).isTrue();
        throttle.recordLoginFailure(failed, "INVALID_CREDENTIALS", request);
        assertThat(throttle.reserveLoginAttempt(successful, request)).isTrue();
        throttle.recordLoginSuccess(successful);

        assertThat(jdbc.sql("""
                        SELECT failure_count FROM authentication_rate_limits
                        WHERE bucket_type = 'LOGIN_IP' AND bucket_key_hash = :key
                        """).param("key", ipBucket).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT failure_count FROM authentication_rate_limits
                        WHERE bucket_type = 'LOGIN_IDENTITY' AND bucket_key_hash = :key
                        """).param("key", failed.identityBucket()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM authentication_rate_limits
                        WHERE bucket_type = 'LOGIN_IDENTITY' AND bucket_key_hash = :key
                        """).param("key", successful.identityBucket()).query(Integer.class).single()).isZero();
    }

    private AuthenticationThrottleService service() {
        byte[] bucketKey = new byte[32];
        java.util.Arrays.fill(bucketKey, (byte) 17);
        return new AuthenticationThrottleService(jdbc, mock(AuditService.class), bucketKey);
    }

    private AuthenticationThrottleService.LoginAttempt attempt(String identityBucket, String ipBucket) {
        return new AuthenticationThrottleService.LoginAttempt(
                new AuthenticationThrottleService.Identity(null, null, null), identityBucket, ipBucket);
    }
}
