package com.autoinvoice.api.customer;

import com.autoinvoice.api.idempotency.IdempotencyExecutor;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.audit.AuditService;
import com.autoinvoice.platform.security.SecretCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class CustomerControllerIntegrationTest {
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
    void archivePersistsAndReplaysNoContentResponseWithoutRepeatingTheMutation() {
        TestCustomer fixture = createCustomer();
        CustomerController controller = controller();
        Authentication authentication = authentication(fixture.actor());
        String key = "archive-customer-" + UUID.randomUUID();
        CustomerController.ArchiveRequest command = new CustomerController.ArchiveRequest("duplicate click");

        var first = controller.archive(authentication, fixture.customerId(), key, "\"0\"", command,
                request(fixture.customerId()));
        var replay = controller.archive(authentication, fixture.customerId(), key, "\"0\"", command,
                request(fixture.customerId()));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(first.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(replay.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(jdbc.sql("SELECT status FROM customers WHERE id = :id")
                .param("id", fixture.customerId()).query(String.class).single()).isEqualTo("ARCHIVED");
        assertThat(jdbc.sql("SELECT version FROM customers WHERE id = :id")
                .param("id", fixture.customerId()).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_logs WHERE tenant_id = :tenantId AND object_id = :id")
                .param("tenantId", fixture.actor().tenantId()).param("id", fixture.customerId())
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("""
                        SELECT response_status FROM idempotency_keys
                        WHERE tenant_id = :tenantId AND actor_id = :actorId AND idempotency_key = :key
                        """)
                .param("tenantId", fixture.actor().tenantId()).param("actorId", fixture.actor().userId())
                .param("key", key).query(Integer.class).single()).isEqualTo(204);
    }

    @Test
    void archiveFingerprintIncludesIfMatchVersion() {
        TestCustomer fixture = createCustomer();
        CustomerController controller = controller();
        Authentication authentication = authentication(fixture.actor());
        String key = "archive-version-" + UUID.randomUUID();
        CustomerController.ArchiveRequest command = new CustomerController.ArchiveRequest("archive");

        controller.archive(authentication, fixture.customerId(), key, "\"0\"", command,
                request(fixture.customerId()));

        assertThatThrownBy(() -> controller.archive(authentication, fixture.customerId(), key, "\"1\"", command,
                request(fixture.customerId())))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).code())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    private CustomerController controller() {
        byte[] rawKey = new byte[32];
        java.util.Arrays.fill(rawKey, (byte) 29);
        String masterKey = Base64.getEncoder().encodeToString(rawKey);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AuditService auditService = new AuditService(jdbc, objectMapper);
        IdempotencyExecutor idempotency = new IdempotencyExecutor(
                jdbc, objectMapper, new SecretCipher(masterKey), masterKey);
        return new CustomerController(jdbc, auditService, idempotency);
    }

    private TestCustomer createCustomer() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        String suffix = tenantId.toString().substring(0, 8);
        String tenantCode = "customer-" + suffix;
        String username = "operator-" + suffix;
        jdbc.sql("INSERT INTO tenants(id, tenant_code, tenant_name) VALUES (:id, :code, 'Customer test')")
                .param("id", tenantId).param("code", tenantCode).update();
        jdbc.sql("""
                        INSERT INTO users(id, tenant_id, username, email, display_name, status)
                        VALUES (:id, :tenantId, :username, :email, 'Customer operator', 'ACTIVE')
                        """)
                .param("id", userId).param("tenantId", tenantId).param("username", username)
                .param("email", username + "@example.invalid").update();
        jdbc.sql("""
                        INSERT INTO customers(
                            id, tenant_id, customer_no, customer_name, customer_type, owner_user_id,
                            default_currency, default_language, default_billing_cycle,
                            default_payment_terms_days, status
                        ) VALUES (
                            :id, :tenantId, :number, 'Replay customer', 'ENTERPRISE', :ownerId,
                            'CNY', 'zh-CN', 'MONTHLY', 7, 'ACTIVE'
                        )
                        """)
                .param("id", customerId).param("tenantId", tenantId).param("number", "CUS-" + suffix)
                .param("ownerId", userId).update();
        AuthenticatedUser actor = new AuthenticatedUser(userId, tenantId, tenantCode, username,
                "Customer operator", "", false, null, false, 1,
                Set.of("customer.write"), true);
        return new TestCustomer(actor, customerId);
    }

    private Authentication authentication(AuthenticatedUser actor) {
        return UsernamePasswordAuthenticationToken.authenticated(actor, null, actor.getAuthorities());
    }

    private MockHttpServletRequest request(UUID customerId) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/customers/" + customerId + "/archive");
        request.addHeader("X-Request-Id", UUID.randomUUID().toString());
        request.addHeader(HttpHeaders.IF_MATCH, "\"0\"");
        return request;
    }

    private record TestCustomer(AuthenticatedUser actor, UUID customerId) {
    }
}
