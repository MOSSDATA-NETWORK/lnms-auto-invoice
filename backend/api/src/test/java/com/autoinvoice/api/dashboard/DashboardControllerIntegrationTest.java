package com.autoinvoice.api.dashboard;

import com.autoinvoice.api.security.AuthenticatedUser;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class DashboardControllerIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.9-alpine");

    private static JdbcClient jdbc;
    private static SingleConnectionDataSource testDataSource;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        testDataSource = new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
        jdbc = JdbcClient.create(testDataSource);
    }

    @AfterAll
    static void closeConnection() {
        testDataSource.destroy();
    }

    @Test
    void receivablesSubtractActiveAllocationsAndRemainSeparatedByCurrency() {
        UUID tenantId = UUID.randomUUID();
        UUID cnyInvoice = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenants(id, tenant_code, tenant_name) VALUES (:id, :code, 'Dashboard test')")
                .param("id", tenantId).param("code", "dashboard-" + tenantId).update();

        jdbc.sql("SET session_replication_role = replica").update();
        try {
            insertInvoice(tenantId, cnyInvoice, "INV-CNY", "CNY", 1_000, "CONFIRMED", "PARTIALLY_PAID");
            insertInvoice(tenantId, UUID.randomUUID(), "INV-USD", "USD", 5_000, "SENT", "UNPAID");
            insertInvoice(tenantId, UUID.randomUUID(), "INV-JPY", "JPY", 700, "REPLACED", "OVERDUE");
            insertInvoice(tenantId, UUID.randomUUID(), "INV-VOID", "CNY", 9_999, "VOIDED", "UNPAID");
            insertInvoice(tenantId, UUID.randomUUID(), "INV-FINALIZING", "CNY", 8_888,
                    "FINALIZING", "UNPAID");
            insertAllocation(tenantId, cnyInvoice, 400, "ACTIVE");
            insertAllocation(tenantId, cnyInvoice, 100, "REVERSED");
        } finally {
            jdbc.sql("SET session_replication_role = DEFAULT").update();
        }

        AuthenticatedUser principal = new AuthenticatedUser(UUID.randomUUID(), tenantId, "tenant", "finance",
                "Finance", "", false, null, false, 1, Set.of("payment.record"), true);
        DashboardController.DashboardSummary summary = new DashboardController(jdbc).summary(
                UsernamePasswordAuthenticationToken.authenticated(principal, "", principal.getAuthorities()));

        assertThat(summary.invoicesFinalizing()).isEqualTo(1);
        assertThat(summary.receivables()).containsExactly(
                new DashboardController.ReceivableBalance("CNY", "¥", 2, 600),
                new DashboardController.ReceivableBalance("JPY", "¥", 0, 700),
                new DashboardController.ReceivableBalance("USD", "$", 2, 5_000));
        assertThat(summary.customers()).isNull();
        assertThat(summary.deadJobs()).isNull();
    }

    private void insertInvoice(UUID tenantId, UUID invoiceId, String number, String currency, long total,
                               String documentStatus, String paymentStatus) {
        jdbc.sql("""
                        INSERT INTO invoices(
                            id, tenant_id, invoice_number, source_preview_id, invoice_profile_id,
                            customer_id, company_id, template_id, template_version_id, approval_instance_id,
                            period_start, period_end, issue_date, due_date, timezone, language, currency_code,
                            subtotal_minor, discount_minor, tax_minor, adjustment_minor, total_minor,
                            party_snapshot_json, profile_snapshot_json, render_model_json, data_snapshot_hash,
                            document_status, send_status, payment_status, finalized_by
                        ) VALUES (
                            :id, :tenantId, :number, :sourcePreviewId, :profileId,
                            :customerId, :companyId, :templateId, :templateVersionId, :approvalInstanceId,
                            now() - interval '1 month', now(), current_date - 30, current_date - 1,
                            'Asia/Shanghai', 'zh-CN', :currency,
                            :total, 0, 0, 0, :total,
                            '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, repeat('0', 64),
                            :documentStatus, 'NOT_QUEUED', :paymentStatus, :finalizedBy
                        )
                        """)
                .param("id", invoiceId).param("tenantId", tenantId).param("number", number)
                .param("sourcePreviewId", UUID.randomUUID()).param("profileId", UUID.randomUUID())
                .param("customerId", UUID.randomUUID()).param("companyId", UUID.randomUUID())
                .param("templateId", UUID.randomUUID()).param("templateVersionId", UUID.randomUUID())
                .param("approvalInstanceId", UUID.randomUUID()).param("currency", currency)
                .param("total", total).param("documentStatus", documentStatus).param("paymentStatus", paymentStatus)
                .param("finalizedBy", UUID.randomUUID()).update();
    }

    private void insertAllocation(UUID tenantId, UUID invoiceId, long amount, String status) {
        jdbc.sql("""
                        INSERT INTO payment_allocations(
                            id, tenant_id, payment_id, invoice_id, amount_minor, status,
                            reversed_by, reversed_at, reversal_reason
                        ) VALUES (
                            :id, :tenantId, :paymentId, :invoiceId, :amount, :status,
                            CASE WHEN :status = 'REVERSED' THEN :actorId ELSE NULL END,
                            CASE WHEN :status = 'REVERSED' THEN now() ELSE NULL END,
                            CASE WHEN :status = 'REVERSED' THEN 'dashboard fixture reversal' ELSE NULL END
                        )
                        """)
                .param("id", UUID.randomUUID()).param("tenantId", tenantId).param("paymentId", UUID.randomUUID())
                .param("invoiceId", invoiceId).param("amount", amount).param("status", status)
                .param("actorId", UUID.randomUUID()).update();
    }
}
