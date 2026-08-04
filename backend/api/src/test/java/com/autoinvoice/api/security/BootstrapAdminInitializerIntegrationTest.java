package com.autoinvoice.api.security;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class BootstrapAdminInitializerIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.9-alpine");

    private static JdbcClient jdbc;
    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
    }

    @Test
    void bootstrapCredentialRequiresChangeAndExpiresWithinTwentyFourHours() {
        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
        String password = "Bootstrap!Pass123";
        BootstrapAdminInitializer initializer = new BootstrapAdminInitializer(
                jdbc, passwordEncoder, new DataSourceTransactionManager(dataSource), true, password);

        initializer.run(new DefaultApplicationArguments(new String[0]));

        BootstrapCredential credential = jdbc.sql("""
                        SELECT password_hash, must_change_password, temporary_password_expires_at
                        FROM users WHERE username = 'admin'
                        """)
                .query((rs, row) -> new BootstrapCredential(
                        rs.getString("password_hash"),
                        rs.getBoolean("must_change_password"),
                        rs.getObject("temporary_password_expires_at", OffsetDateTime.class)))
                .single();
        assertThat(passwordEncoder.matches(password, credential.passwordHash())).isTrue();
        assertThat(credential.mustChangePassword()).isTrue();
        assertThat(credential.expiresAt()).isBetween(
                OffsetDateTime.now().plusHours(23), OffsetDateTime.now().plusHours(25));
    }

    private record BootstrapCredential(String passwordHash, boolean mustChangePassword,
                                       OffsetDateTime expiresAt) {
    }
}
