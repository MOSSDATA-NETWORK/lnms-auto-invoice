package com.autoinvoice.worker.render;

import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.outbox.OutboxService;
import com.autoinvoice.worker.storage.ObjectStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class RenderedInvoicePersisterIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.9-alpine");

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static TransactionTemplate transactions;
    private static RenderedInvoicePersister persister;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        persister = new RenderedInvoicePersister(
                jdbc, new OutboxService(jdbc, new ObjectMapper().findAndRegisterModules()));
    }

    @Test
    void confirmsReplacementLinksItAndMarksTheVoidedOriginInOneTransaction() throws Exception {
        Fixture fixture = fixture("VOIDED");

        UUID fileId = transactions.execute(status -> persister.persist(
                fixture.renderSource(), storedObject(fixture), 128, "b".repeat(64), "Chromium test"));

        assertThat(fileId).isNotNull();
        assertThat(documentStatus(fixture.originInvoiceId())).isEqualTo("REPLACED");
        assertThat(documentStatus(fixture.replacementInvoiceId())).isEqualTo("CONFIRMED");
        assertThat(jdbc.sql("SELECT status FROM invoice_previews WHERE id = :id")
                .param("id", fixture.previewId()).query(String.class).single()).isEqualTo("FINALIZED");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM invoice_relations
                        WHERE tenant_id = :tenantId AND source_invoice_id = :originId
                          AND target_invoice_id = :replacementId AND relation_type = 'REPLACES'
                        """)
                .param("tenantId", fixture.tenantId()).param("originId", fixture.originInvoiceId())
                .param("replacementId", fixture.replacementInvoiceId())
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM outbox_events
                        WHERE tenant_id = :tenantId AND aggregate_id = :invoiceId
                          AND event_type = 'invoice.confirmed'
                        """)
                .param("tenantId", fixture.tenantId()).param("invoiceId", fixture.replacementInvoiceId())
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void failedReplacementConfirmationLeavesBothInvoicesAndEvidenceUnchanged() throws Exception {
        Fixture fixture = fixture("CONFIRMED");

        assertThatThrownBy(() -> transactions.execute(status -> persister.persist(
                fixture.renderSource(), storedObject(fixture), 128, "c".repeat(64), "Chromium test")))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("ORIGIN_INVOICE_NOT_REPLACEABLE"));

        assertThat(documentStatus(fixture.originInvoiceId())).isEqualTo("CONFIRMED");
        assertThat(documentStatus(fixture.replacementInvoiceId())).isEqualTo("FINALIZING");
        assertThat(jdbc.sql("SELECT count(*) FROM invoice_relations WHERE tenant_id = :tenantId")
                .param("tenantId", fixture.tenantId()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM invoice_files WHERE tenant_id = :tenantId")
                .param("tenantId", fixture.tenantId()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE tenant_id = :tenantId")
                .param("tenantId", fixture.tenantId()).query(Integer.class).single()).isZero();
    }

    private static Fixture fixture(String originStatus) throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        UUID templateVersionId = UUID.randomUUID();
        UUID previewId = UUID.randomUUID();
        UUID originInvoiceId = UUID.randomUUID();
        UUID replacementInvoiceId = UUID.randomUUID();
        String suffix = tenantId.toString().replace("-", "");

        jdbc.sql("INSERT INTO tenants(id, tenant_code, tenant_name) VALUES (:id, :code, 'Persister tenant')")
                .param("id", tenantId).param("code", "P_" + suffix).update();
        jdbc.sql("""
                        INSERT INTO users(id, tenant_id, username, email, display_name)
                        VALUES (:id, :tenantId, :username, :email, 'Persister actor')
                        """)
                .param("id", actorId).param("tenantId", tenantId)
                .param("username", "persister-" + suffix)
                .param("email", "persister-" + suffix + "@example.invalid").update();
        jdbc.sql("""
                        INSERT INTO invoice_templates(id, tenant_id, template_code, template_name, created_by)
                        VALUES (:id, :tenantId, :code, 'Persister template', :actorId)
                        """)
                .param("id", templateId).param("tenantId", tenantId)
                .param("code", "TPL_" + suffix).param("actorId", actorId).update();
        jdbc.sql("""
                        INSERT INTO invoice_template_versions(
                            id, tenant_id, template_id, version_no, html_content,
                            schema_json, content_sha256, status, created_by
                        ) VALUES (
                            :id, :tenantId, :templateId, 1, '<html></html>',
                            '{}'::jsonb, repeat('a', 64), 'DRAFT', :actorId
                        )
                        """)
                .param("id", templateVersionId).param("tenantId", tenantId)
                .param("templateId", templateId).param("actorId", actorId).update();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
            }
            JdbcClient local = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            try {
                local.sql("""
                                INSERT INTO invoice_previews(
                                    id, tenant_id, preview_number, invoice_profile_id, customer_id, company_id,
                                    template_id, template_version_id, origin_invoice_id,
                                    period_start, period_end, issue_date, due_date, timezone, language,
                                    currency_code, subtotal_minor, discount_minor, tax_minor,
                                    adjustment_minor, total_minor, profile_snapshot_json, party_snapshot_json,
                                    render_model_json, status, approval_revision, approved_at, created_by, version
                                ) VALUES (
                                    :id, :tenantId, :number, :profileId, :customerId, :companyId,
                                    :templateId, :templateVersionId, :originId,
                                    now() - interval '1 month', now(), current_date, current_date + 7,
                                    'Asia/Shanghai', 'zh-CN', 'CNY', 0, 0, 0, 0, 0,
                                    '{}'::jsonb, '{}'::jsonb, '{}'::jsonb,
                                    'FINALIZING', 1, now(), :actorId, 2
                                )
                                """)
                        .param("id", previewId).param("tenantId", tenantId)
                        .param("number", "PRE_" + suffix).param("profileId", UUID.randomUUID())
                        .param("customerId", UUID.randomUUID()).param("companyId", UUID.randomUUID())
                        .param("templateId", templateId).param("templateVersionId", templateVersionId)
                        .param("originId", originInvoiceId).param("actorId", actorId).update();
                local.sql("""
                                INSERT INTO invoices(
                                    id, tenant_id, invoice_number, source_preview_id, invoice_profile_id,
                                    customer_id, company_id, template_id, template_version_id,
                                    approval_instance_id, period_start, period_end, issue_date, due_date,
                                    timezone, language, currency_code, subtotal_minor, discount_minor,
                                    tax_minor, adjustment_minor, total_minor, party_snapshot_json,
                                    profile_snapshot_json, render_model_json, data_snapshot_hash,
                                    document_status, send_status, payment_status, finalized_by,
                                    created_at, updated_at, confirmed_at, voided_at, version
                                ) VALUES (
                                    :id, :tenantId, :number, :sourcePreviewId, :profileId,
                                    :customerId, :companyId, :templateId, :templateVersionId,
                                    :approvalId, now() - interval '2 months', now() - interval '1 month',
                                    current_date - 60, current_date - 30, 'Asia/Shanghai', 'zh-CN', 'CNY',
                                    0, 0, 0, 0, 0, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb,
                                    repeat('0', 64), :status, 'NOT_QUEUED', 'UNPAID', :actorId,
                                    now() - interval '3 minutes', now() - interval '1 minute',
                                    CASE WHEN :status = 'CONFIRMED' THEN now() - interval '2 minutes'
                                         ELSE now() - interval '2 minutes' END,
                                    CASE WHEN :status = 'VOIDED' THEN now() - interval '1 minute' ELSE NULL END,
                                    CASE WHEN :status = 'VOIDED' THEN 2 ELSE 1 END
                                )
                                """)
                        .param("id", originInvoiceId).param("tenantId", tenantId)
                        .param("number", "INV_ORIGIN_" + suffix).param("sourcePreviewId", UUID.randomUUID())
                        .param("profileId", UUID.randomUUID()).param("customerId", UUID.randomUUID())
                        .param("companyId", UUID.randomUUID()).param("templateId", templateId)
                        .param("templateVersionId", templateVersionId).param("approvalId", UUID.randomUUID())
                        .param("status", originStatus).param("actorId", actorId).update();
                local.sql("""
                                INSERT INTO invoices(
                                    id, tenant_id, invoice_number, source_preview_id, invoice_profile_id,
                                    customer_id, company_id, template_id, template_version_id,
                                    approval_instance_id, period_start, period_end, issue_date, due_date,
                                    timezone, language, currency_code, subtotal_minor, discount_minor,
                                    tax_minor, adjustment_minor, total_minor, party_snapshot_json,
                                    profile_snapshot_json, render_model_json, data_snapshot_hash,
                                    document_status, send_status, payment_status, finalized_by, version
                                )
                                SELECT :id, preview.tenant_id, :number, preview.id,
                                       preview.invoice_profile_id, preview.customer_id, preview.company_id,
                                       preview.template_id, preview.template_version_id, :approvalId,
                                       preview.period_start, preview.period_end, preview.issue_date,
                                       preview.due_date, preview.timezone, preview.language,
                                       preview.currency_code, preview.subtotal_minor, preview.discount_minor,
                                       preview.tax_minor, preview.adjustment_minor, preview.total_minor,
                                       preview.party_snapshot_json, preview.profile_snapshot_json,
                                       preview.render_model_json, repeat('0', 64),
                                       'FINALIZING', 'NOT_QUEUED', 'UNPAID', :actorId, 0
                                FROM invoice_previews preview
                                WHERE preview.tenant_id = :tenantId AND preview.id = :previewId
                                """)
                        .param("id", replacementInvoiceId).param("number", "INV_REPLACEMENT_" + suffix)
                        .param("approvalId", UUID.randomUUID()).param("actorId", actorId)
                        .param("tenantId", tenantId).param("previewId", previewId).update();
                connection.commit();
            } catch (RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }

        InvoiceRenderSource renderSource = new InvoiceRenderSource(
                tenantId, replacementInvoiceId, "INV_REPLACEMENT_" + suffix,
                templateVersionId, actorId, "0".repeat(64), "FINALIZING",
                new ObjectMapper().createObjectNode(), "<p>invoice</p>", "");
        return new Fixture(tenantId, previewId, originInvoiceId, replacementInvoiceId, renderSource);
    }

    private static ObjectStorage.StoredObject storedObject(Fixture fixture) {
        return new ObjectStorage.StoredObject(
                "MINIO", "invoice-test", fixture.tenantId() + "/" + fixture.replacementInvoiceId() + ".pdf");
    }

    private static String documentStatus(UUID invoiceId) {
        return jdbc.sql("SELECT document_status FROM invoices WHERE id = :id")
                .param("id", invoiceId).query(String.class).single();
    }

    private record Fixture(
            UUID tenantId,
            UUID previewId,
            UUID originInvoiceId,
            UUID replacementInvoiceId,
            InvoiceRenderSource renderSource) {
    }
}
