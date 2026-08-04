package com.autoinvoice.api.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class InvoiceLifecycleDatabaseIntegrityIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.9-alpine");

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
    }

    @Test
    void rejectsEveryNonInitialFormalInvoiceStateAndAcceptsFinalizingDefaults() throws Exception {
        Fixture fixture = fixture();
        OffsetDateTime lifecycleTime = OffsetDateTime.now();
        List<InitialState> invalidStates = List.of(
                new InitialState("SENT", "NOT_QUEUED", "UNPAID", 0, null, null, null, null),
                new InitialState("FINALIZING", "SENT", "UNPAID", 0, null, null, null, null),
                new InitialState("FINALIZING", "NOT_QUEUED", "PAID", 0, null, null, null, null),
                new InitialState("FINALIZING", "NOT_QUEUED", "UNPAID", 1, null, null, null, null),
                new InitialState("FINALIZING", "NOT_QUEUED", "UNPAID", 0,
                        lifecycleTime, null, null, null),
                new InitialState("FINALIZING", "NOT_QUEUED", "UNPAID", 0,
                        null, lifecycleTime, null, null),
                new InitialState("FINALIZING", "NOT_QUEUED", "UNPAID", 0,
                        null, null, lifecycleTime, null),
                new InitialState("FINALIZING", "NOT_QUEUED", "UNPAID", 0,
                        null, null, null, lifecycleTime));

        for (InitialState state : invalidStates) {
            UUID invoiceId = UUID.randomUUID();
            assertThatThrownBy(() -> insertInvoice(fixture, invoiceId, state))
                    .isInstanceOf(SQLException.class)
                    .satisfies(exception -> assertThat(((SQLException) exception).getSQLState())
                            .isEqualTo("23514"));
            assertThat(invoiceCount(invoiceId)).isZero();
        }

        UUID validInvoiceId = UUID.randomUUID();
        assertThat(insertInvoice(fixture, validInvoiceId, InitialState.valid())).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT document_status || '|' || send_status || '|' || payment_status || '|' || version
                        FROM invoices WHERE id = :id
                        """)
                .param("id", validInvoiceId).query(String.class).single())
                .isEqualTo("FINALIZING|NOT_QUEUED|UNPAID|0");
    }

    @Test
    void formalInvoiceRequiresTheCurrentApprovedPreviewAndAnExactFrozenHeader() throws Exception {
        Fixture pendingApproval = fixture();
        replicaUpdate("""
                UPDATE approval_instances
                SET status = 'PENDING', completed_at = NULL
                WHERE id = ?
                """, pendingApproval.approvalInstanceId());
        assertSqlState("23514", () -> insertInvoice(
                pendingApproval, UUID.randomUUID(), InitialState.valid()));

        Fixture wrongPreview = fixture();
        replicaUpdate("UPDATE approval_instances SET invoice_preview_id = ? WHERE id = ?",
                UUID.randomUUID(), wrongPreview.approvalInstanceId());
        assertSqlState("23514", () -> insertInvoice(
                wrongPreview, UUID.randomUUID(), InitialState.valid()));

        Fixture staleApproval = fixture();
        replicaUpdate("UPDATE approval_instances SET preview_version = 0 WHERE id = ?",
                staleApproval.approvalInstanceId());
        assertSqlState("23514", () -> insertInvoice(
                staleApproval, UUID.randomUUID(), InitialState.valid()));

        Fixture nonFinalizingPreview = fixture();
        replicaUpdate("UPDATE invoice_previews SET status = 'APPROVED', version = 1 WHERE id = ?",
                nonFinalizingPreview.previewId());
        assertSqlState("23514", () -> insertInvoice(
                nonFinalizingPreview, UUID.randomUUID(), InitialState.valid()));

        Fixture tamperedHeader = fixture();
        replicaUpdate("""
                UPDATE invoice_previews
                SET subtotal_minor = 101, total_minor = 101
                WHERE id = ?
                """, tamperedHeader.previewId());
        assertSqlState("23514", () -> insertInvoice(
                tamperedHeader, UUID.randomUUID(), InitialState.valid()));
    }

    @Test
    void lifecycleUpdatesRequirePdfEvidenceCoherentTimesAndMonotonicVersion() throws Exception {
        Fixture fixture = fixture();
        UUID invoiceId = UUID.randomUUID();
        assertThat(insertInvoice(fixture, invoiceId, InitialState.valid())).isEqualTo(1);

        assertSqlState("23514", () -> updateInvoice(invoiceId, """
                document_status = 'CONFIRMED', confirmed_at = clock_timestamp(),
                updated_at = clock_timestamp(), version = version + 1
                """));

        insertPdfEvidence(fixture, invoiceId);
        assertSqlState("23514", () -> updateInvoice(invoiceId, """
                document_status = 'CONFIRMED', updated_at = clock_timestamp(), version = version + 1
                """));
        assertThat(updateInvoice(invoiceId, """
                document_status = 'CONFIRMED', confirmed_at = clock_timestamp(),
                updated_at = clock_timestamp(), version = version + 1
                """)).isEqualTo(1);

        assertSqlState("23514", () -> updateInvoice(invoiceId, """
                confirmed_at = confirmed_at + interval '1 second',
                updated_at = clock_timestamp(), version = version + 1
                """));
        assertSqlState("23514", () -> updateInvoice(invoiceId, """
                send_status = 'SENT', sent_at = clock_timestamp(),
                updated_at = clock_timestamp(), version = version + 1
                """));
        assertSqlState("23514", () -> updateInvoice(invoiceId, """
                send_status = 'PARTIALLY_SENT', updated_at = clock_timestamp(), version = version + 1
                """));
        assertSqlState("23514", () -> updateInvoice(invoiceId, """
                payment_status = 'PAID', updated_at = clock_timestamp(), version = version + 1
                """));
        assertSqlState("23514", () -> updateInvoice(invoiceId, """
                payment_status = 'PAID', paid_at = clock_timestamp(),
                updated_at = clock_timestamp(), version = version + 1
                """));
        assertSqlState("23514", () -> updateInvoice(invoiceId, """
                send_status = 'QUEUED', updated_at = updated_at, version = version
                """));
        assertSqlState("23514", () -> updateInvoice(invoiceId, """
                send_status = 'QUEUED', updated_at = updated_at - interval '1 second', version = version + 1
                """));

        assertThat(updateInvoice(invoiceId, """
                document_status = 'SENT', send_status = 'SENT', sent_at = clock_timestamp(),
                updated_at = clock_timestamp(), version = version + 1
                """)).isEqualTo(1);
        assertSqlState("23514", () -> updateInvoice(invoiceId, """
                sent_at = sent_at + interval '1 second',
                updated_at = clock_timestamp(), version = version + 1
                """));
        assertSqlState("23514", () -> updateInvoice(invoiceId, """
                document_status = 'VOIDED', updated_at = clock_timestamp(), version = version + 1
                """));
        assertSqlState("23514", () -> updateInvoice(invoiceId, """
                document_status = 'VOIDED', confirmed_at = NULL, voided_at = clock_timestamp(),
                updated_at = clock_timestamp(), version = version + 1
                """));
        assertSqlState("23514", () -> updateInvoice(invoiceId, """
                document_status = 'REPLACED', voided_at = clock_timestamp(),
                updated_at = clock_timestamp(), version = version + 1
                """));
        assertThat(updateInvoice(invoiceId, """
                document_status = 'VOIDED', voided_at = clock_timestamp(),
                updated_at = clock_timestamp(), version = version + 1
                """)).isEqualTo(1);
        assertSqlState("23514", () -> updateInvoice(invoiceId, """
                document_status = 'REPLACED', updated_at = clock_timestamp(), version = version + 1
                """));

        Fixture correction = correctionFixture(fixture, invoiceId);
        UUID replacementInvoiceId = UUID.randomUUID();
        assertThat(insertInvoice(correction, replacementInvoiceId, InitialState.valid())).isEqualTo(1);
        insertPdfEvidence(correction, replacementInvoiceId);
        confirmCorrection(correction, replacementInvoiceId, invoiceId);

        assertThat(jdbc.sql("SELECT document_status || '|' || version FROM invoices WHERE id = :id")
                .param("id", invoiceId).query(String.class).single()).isEqualTo("REPLACED|4");
    }

    @Test
    void formalLinesMustExactlyAndCompletelyCopyTheFrozenApprovedPreview() throws Exception {
        Fixture fixture = fixture();
        jdbc.sql("UPDATE invoice_previews SET status = 'APPROVED', version = 1 WHERE id = :id")
                .param("id", fixture.previewId()).update();
        UUID includedItemId = insertPreviewItem(fixture, 1, "included-line");
        UUID excludedItemId = insertPreviewItem(fixture, 2, "excluded-line");
        jdbc.sql("""
                        INSERT INTO invoice_preview_exclusions(
                            id, tenant_id, invoice_preview_id, invoice_preview_item_id, reason, created_by
                        ) VALUES (:id, :tenantId, :previewId, :itemId, 'excluded by reviewer', :actorId)
                        """)
                .param("id", UUID.randomUUID()).param("tenantId", fixture.tenantId())
                .param("previewId", fixture.previewId()).param("itemId", excludedItemId)
                .param("actorId", fixture.actorId()).update();
        UUID adjustmentId = insertPreviewAdjustment(fixture);
        jdbc.sql("UPDATE invoice_previews SET status = 'FINALIZING', version = 2 WHERE id = :id")
                .param("id", fixture.previewId()).update();

        assertSqlState("55000", () -> updatePreviewItemTotal(
                fixture.tenantId(), includedItemId, 101));

        UUID invoiceId = UUID.randomUUID();
        assertThat(insertInvoice(fixture, invoiceId, InitialState.valid())).isEqualTo(1);
        assertSqlState("23514", () -> insertFormalItem(fixture, invoiceId, includedItemId, 1));
        assertSqlState("23514", () -> insertFormalItem(fixture, invoiceId, excludedItemId, 0));
        assertThat(insertFormalItem(fixture, invoiceId, includedItemId, 0)).isEqualTo(1);
        assertSqlState("23505", () -> insertFormalItem(fixture, invoiceId, includedItemId, 0));

        assertSqlState("23514", () -> insertFormalAdjustment(fixture, invoiceId, adjustmentId, 1));
        insertPdfEvidence(fixture, invoiceId);
        assertSqlState("23514", () -> updateInvoice(invoiceId, """
                document_status = 'CONFIRMED', confirmed_at = clock_timestamp(),
                updated_at = clock_timestamp(), version = version + 1
                """));

        assertThat(insertFormalAdjustment(fixture, invoiceId, adjustmentId, 0)).isEqualTo(1);
        assertThat(updateInvoice(invoiceId, """
                document_status = 'CONFIRMED', confirmed_at = clock_timestamp(),
                updated_at = clock_timestamp(), version = version + 1
                """)).isEqualTo(1);
        assertSqlState("23514", () -> insertFormalItem(fixture, invoiceId, includedItemId, 0));
    }

    @Test
    void deletedPdfEvidenceCannotConfirmAFormalInvoice() throws Exception {
        Fixture fixture = fixture();
        UUID invoiceId = UUID.randomUUID();
        assertThat(insertInvoice(fixture, invoiceId, InitialState.valid())).isEqualTo(1);
        UUID fileId = insertPdfEvidence(fixture, invoiceId);
        replicaUpdate("UPDATE files SET deleted_at = clock_timestamp() WHERE id = ?", fileId);

        assertSqlState("23514", () -> updateInvoice(invoiceId, """
                document_status = 'CONFIRMED', confirmed_at = clock_timestamp(),
                updated_at = clock_timestamp(), version = version + 1
                """));
    }

    @Test
    void activePaymentAllocationBlocksVoidingUntilItIsReversed() throws Exception {
        Fixture fixture = fixture();
        UUID invoiceId = confirmedInvoice(fixture);
        UUID paymentId = insertPayment(fixture, "void-guard");
        UUID allocationId = UUID.randomUUID();

        assertThat(insertAllocation(fixture, paymentId, invoiceId, allocationId, 40)).isEqualTo(1);
        assertSqlState("23514", () -> updateInvoice(invoiceId, """
                document_status = 'VOIDED', voided_at = clock_timestamp(),
                updated_at = clock_timestamp(), version = version + 1
                """));

        assertThat(jdbc.sql("""
                        UPDATE payment_allocations
                        SET status = 'REVERSED', reversed_by = :actorId,
                            reversed_at = clock_timestamp(), reversal_reason = 'void invoice'
                        WHERE tenant_id = :tenantId AND id = :allocationId
                        """)
                .param("actorId", fixture.actorId()).param("tenantId", fixture.tenantId())
                .param("allocationId", allocationId).update()).isEqualTo(1);
        assertThat(updateInvoice(invoiceId, """
                document_status = 'VOIDED', voided_at = clock_timestamp(),
                updated_at = clock_timestamp(), version = version + 1
                """)).isEqualTo(1);
    }

    @Test
    void allocationWaitingBehindInvoiceLockIsRejectedAfterVoidCommits() throws Exception {
        Fixture fixture = fixture();
        UUID invoiceId = confirmedInvoice(fixture);
        UUID paymentId = insertPayment(fixture, "void-race");
        UUID allocationId = UUID.randomUUID();

        try (var executor = Executors.newSingleThreadExecutor();
             Connection voiding = dataSource.getConnection()) {
            voiding.setAutoCommit(false);
            try (PreparedStatement statement = voiding.prepareStatement("""
                    SELECT id FROM invoices WHERE tenant_id = ? AND id = ? FOR NO KEY UPDATE
                    """)) {
                statement.setObject(1, fixture.tenantId());
                statement.setObject(2, invoiceId);
                assertThat(statement.executeQuery().next()).isTrue();
            }

            CountDownLatch insertStarted = new CountDownLatch(1);
            var allocation = executor.submit(() -> {
                insertStarted.countDown();
                try {
                    insertAllocation(fixture, paymentId, invoiceId, allocationId, 40);
                    return new SqlResult(true, null);
                } catch (SQLException exception) {
                    return new SqlResult(false, exception.getSQLState());
                }
            });

            assertThat(insertStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> allocation.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(updateInvoice(voiding, invoiceId, """
                    document_status = 'VOIDED', voided_at = clock_timestamp(),
                    updated_at = clock_timestamp(), version = version + 1
                    """)).isEqualTo(1);
            voiding.commit();

            assertThat(allocation.get(5, TimeUnit.SECONDS)).isEqualTo(new SqlResult(false, "23514"));
        }
        assertThat(invoiceCount(invoiceId)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM payment_allocations WHERE id = :id")
                .param("id", allocationId).query(Integer.class).single()).isZero();
    }

    private static UUID insertPreviewItem(Fixture fixture, int lineNo, String sourceKey) {
        UUID itemId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO invoice_preview_items(
                            id, tenant_id, invoice_preview_id, source_key, line_no,
                            item_name, item_description, billing_period_start, billing_period_end,
                            quantity, unit, unit_price, subtotal_minor, discount_minor, tax_minor,
                            total_minor, calculation_snapshot_json, display_json
                        ) VALUES (
                            :id, :tenantId, :previewId, :sourceKey, :lineNo,
                            'Fixed service', 'Approved fixed service',
                            TIMESTAMPTZ '2026-07-01 00:00:00+00',
                            TIMESTAMPTZ '2026-08-01 00:00:00+00',
                            1, 'UNIT', 100, 100, 0, 0, 100,
                            '{"formula":"fixed"}'::jsonb, '{"group":"service"}'::jsonb
                        )
                        """)
                .param("id", itemId).param("tenantId", fixture.tenantId())
                .param("previewId", fixture.previewId()).param("sourceKey", sourceKey)
                .param("lineNo", lineNo).update();
        return itemId;
    }

    private static UUID insertPreviewAdjustment(Fixture fixture) {
        UUID adjustmentId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO invoice_preview_adjustments(
                            id, tenant_id, invoice_preview_id, adjustment_type, description,
                            amount_minor, included_in_tax_base, reason, status, created_by, approved_by
                        ) VALUES (
                            :id, :tenantId, :previewId, 'CUSTOM', 'Approved note',
                            0, true, 'reviewed adjustment', 'ACTIVE', :actorId, :actorId
                        )
                        """)
                .param("id", adjustmentId).param("tenantId", fixture.tenantId())
                .param("previewId", fixture.previewId()).param("actorId", fixture.actorId()).update();
        return adjustmentId;
    }

    private static int insertFormalItem(
            Fixture fixture, UUID invoiceId, UUID sourceItemId, long totalDelta) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO invoice_items(
                         id, tenant_id, invoice_id, source_preview_item_id,
                         contract_item_id, service_id, pricing_rule_version_id, usage_snapshot_id,
                         source_key, line_no, item_name, item_description,
                         billing_period_start, billing_period_end, raw_usage, converted_usage,
                         rounded_usage, billing_usage, quantity, unit, unit_price,
                         subtotal_minor, discount_minor, tax_minor, total_minor,
                         calculation_snapshot_json, display_json
                     )
                     SELECT ?, source.tenant_id, ?, source.id,
                            source.contract_item_id, source.service_id,
                            source.pricing_rule_version_id, source.usage_snapshot_id,
                            source.source_key, source.line_no, source.item_name, source.item_description,
                            source.billing_period_start, source.billing_period_end, source.raw_usage,
                            source.converted_usage, source.rounded_usage, source.billing_usage,
                            source.quantity, source.unit, source.unit_price,
                            source.subtotal_minor, source.discount_minor, source.tax_minor,
                            source.total_minor + ?, source.calculation_snapshot_json, source.display_json
                     FROM invoice_preview_items source
                     WHERE source.tenant_id = ? AND source.id = ?
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, invoiceId);
            statement.setLong(3, totalDelta);
            statement.setObject(4, fixture.tenantId());
            statement.setObject(5, sourceItemId);
            return statement.executeUpdate();
        }
    }

    private static int insertFormalAdjustment(
            Fixture fixture, UUID invoiceId, UUID sourceAdjustmentId, long amountDelta) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO invoice_adjustments(
                         id, tenant_id, invoice_id, source_preview_adjustment_id,
                         adjustment_type, description, amount_minor, tax_rate,
                         included_in_tax_base, reason, attachment_file_id, operator_snapshot_json
                     )
                     SELECT ?, source.tenant_id, ?, source.id,
                            source.adjustment_type, source.description, source.amount_minor + ?,
                            source.tax_rate, source.included_in_tax_base, source.reason,
                            source.attachment_file_id,
                            jsonb_build_object(
                                'created_by', source.created_by,
                                'approved_by', source.approved_by,
                                'created_at', source.created_at
                            )
                     FROM invoice_preview_adjustments source
                     WHERE source.tenant_id = ? AND source.id = ?
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, invoiceId);
            statement.setLong(3, amountDelta);
            statement.setObject(4, fixture.tenantId());
            statement.setObject(5, sourceAdjustmentId);
            return statement.executeUpdate();
        }
    }

    private static int updatePreviewItemTotal(UUID tenantId, UUID itemId, long totalMinor) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE invoice_preview_items SET total_minor = ?
                     WHERE tenant_id = ? AND id = ?
                     """)) {
            statement.setLong(1, totalMinor);
            statement.setObject(2, tenantId);
            statement.setObject(3, itemId);
            return statement.executeUpdate();
        }
    }

    private static Fixture correctionFixture(Fixture source, UUID originInvoiceId) throws Exception {
        UUID previewId = UUID.randomUUID();
        UUID approvalInstanceId = UUID.randomUUID();
        String suffix = compact(previewId);
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
                                    template_id, template_version_id, approval_workflow_version_id,
                                    origin_invoice_id, period_start, period_end, issue_date, due_date,
                                    timezone, language, currency_code, exchange_rate,
                                    subtotal_minor, discount_minor, tax_minor, adjustment_minor, total_minor,
                                    profile_snapshot_json, party_snapshot_json, render_model_json,
                                    status, approval_revision, approved_at, created_by, version
                                )
                                SELECT :id, source.tenant_id, :number, source.invoice_profile_id,
                                       source.customer_id, source.company_id, source.template_id,
                                       source.template_version_id, source.approval_workflow_version_id,
                                       :originInvoiceId, source.period_start, source.period_end,
                                       source.issue_date, source.due_date, source.timezone, source.language,
                                       source.currency_code, source.exchange_rate,
                                       source.subtotal_minor, source.discount_minor, source.tax_minor,
                                       source.adjustment_minor, source.total_minor,
                                       source.profile_snapshot_json, source.party_snapshot_json,
                                       source.render_model_json, 'FINALIZING', 1, clock_timestamp(),
                                       :actorId, 2
                                FROM invoice_previews source
                                WHERE source.tenant_id = :tenantId AND source.id = :sourcePreviewId
                                """)
                        .param("id", previewId).param("number", "CORR_" + suffix)
                        .param("originInvoiceId", originInvoiceId).param("actorId", source.actorId())
                        .param("tenantId", source.tenantId()).param("sourcePreviewId", source.previewId())
                        .update();
                local.sql("""
                                INSERT INTO approval_instances(
                                    id, tenant_id, invoice_preview_id, workflow_version_id,
                                    preview_version, approval_revision, status, requested_by, completed_at
                                )
                                SELECT :id, preview.tenant_id, preview.id,
                                       preview.approval_workflow_version_id,
                                       1, 1, 'APPROVED', :actorId, clock_timestamp()
                                FROM invoice_previews preview
                                WHERE preview.tenant_id = :tenantId AND preview.id = :previewId
                                """)
                        .param("id", approvalInstanceId).param("actorId", source.actorId())
                        .param("tenantId", source.tenantId()).param("previewId", previewId).update();
                connection.commit();
            } catch (RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
        return new Fixture(source.tenantId(), source.actorId(), source.customerId(), source.companyId(),
                source.templateId(), source.templateVersionId(), source.profileId(),
                previewId, approvalInstanceId);
    }

    private static void confirmCorrection(
            Fixture correction, UUID replacementInvoiceId, UUID originInvoiceId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                assertThat(updateInvoice(connection, replacementInvoiceId, """
                        document_status = 'CONFIRMED', confirmed_at = clock_timestamp(),
                        updated_at = clock_timestamp(), version = version + 1
                        """)).isEqualTo(1);
                try (PreparedStatement relation = connection.prepareStatement("""
                        INSERT INTO invoice_relations(
                            id, tenant_id, source_invoice_id, target_invoice_id,
                            relation_type, reason, created_by
                        ) VALUES (?, ?, ?, ?, 'REPLACES', 'confirmed correction', ?)
                        """)) {
                    relation.setObject(1, UUID.randomUUID());
                    relation.setObject(2, correction.tenantId());
                    relation.setObject(3, originInvoiceId);
                    relation.setObject(4, replacementInvoiceId);
                    relation.setObject(5, correction.actorId());
                    assertThat(relation.executeUpdate()).isEqualTo(1);
                }
                assertThat(updateInvoice(connection, originInvoiceId, """
                        document_status = 'REPLACED', updated_at = clock_timestamp(), version = version + 1
                        """)).isEqualTo(1);
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static Fixture fixture() throws Exception {
        Fixture fixture = new Fixture(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String suffix = compact(fixture.tenantId());

        jdbc.sql("INSERT INTO tenants(id, tenant_code, tenant_name) VALUES (:id, :code, 'Invoice guard tenant')")
                .param("id", fixture.tenantId()).param("code", "T_" + suffix).update();
        jdbc.sql("""
                        INSERT INTO users(id, tenant_id, username, email, display_name)
                        VALUES (:id, :tenantId, :username, :email, 'Invoice guard operator')
                        """)
                .param("id", fixture.actorId()).param("tenantId", fixture.tenantId())
                .param("username", "invoice-guard-" + suffix)
                .param("email", "invoice-guard-" + suffix + "@example.invalid").update();
        jdbc.sql("""
                        INSERT INTO customers(id, tenant_id, customer_no, customer_name, default_currency)
                        VALUES (:id, :tenantId, :number, 'Invoice guard customer', 'CNY')
                        """)
                .param("id", fixture.customerId()).param("tenantId", fixture.tenantId())
                .param("number", "CUST_" + suffix).update();
        jdbc.sql("""
                        INSERT INTO companies(id, tenant_id, customer_id, company_code, company_name, default_currency)
                        VALUES (:id, :tenantId, :customerId, :code, 'Invoice guard company', 'CNY')
                        """)
                .param("id", fixture.companyId()).param("tenantId", fixture.tenantId())
                .param("customerId", fixture.customerId()).param("code", "COMP_" + suffix).update();

        insertDeepParents(fixture, suffix);
        return fixture;
    }

    private static void insertDeepParents(Fixture fixture, String suffix) throws Exception {
        UUID workflowVersionId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
            }
            JdbcClient local = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            try {
                local.sql("""
                                INSERT INTO invoice_templates(id, tenant_id, template_code, template_name, created_by)
                                VALUES (:id, :tenantId, :code, 'Invoice guard template', :actorId)
                                """)
                        .param("id", fixture.templateId()).param("tenantId", fixture.tenantId())
                        .param("code", "TPL_" + suffix).param("actorId", fixture.actorId()).update();
                local.sql("""
                                INSERT INTO invoice_template_versions(
                                    id, tenant_id, template_id, version_no, html_content, schema_json,
                                    content_sha256, status, created_by
                                ) VALUES (
                                    :id, :tenantId, :templateId, 1, '<html></html>', '{}'::jsonb,
                                    repeat('a', 64), 'PUBLISHED', :actorId
                                )
                                """)
                        .param("id", fixture.templateVersionId()).param("tenantId", fixture.tenantId())
                        .param("templateId", fixture.templateId()).param("actorId", fixture.actorId()).update();
                local.sql("""
                                INSERT INTO invoice_profiles(
                                    id, tenant_id, profile_code, profile_name, customer_id, company_id,
                                    template_id, currency_code, invoice_number_rule
                                ) VALUES (
                                    :id, :tenantId, :code, 'Invoice guard profile', :customerId, :companyId,
                                    :templateId, 'CNY', 'INV-{period}'
                                )
                                """)
                        .param("id", fixture.profileId()).param("tenantId", fixture.tenantId())
                        .param("code", "PROFILE_" + suffix).param("customerId", fixture.customerId())
                        .param("companyId", fixture.companyId()).param("templateId", fixture.templateId()).update();
                local.sql("""
                                INSERT INTO invoice_previews(
                                    id, tenant_id, preview_number, invoice_profile_id, customer_id, company_id,
                                    template_id, template_version_id, approval_workflow_version_id,
                                    period_start, period_end, issue_date, due_date,
                                    timezone, language, currency_code, profile_snapshot_json, party_snapshot_json,
                                    subtotal_minor, total_minor, status, approval_revision, approved_at,
                                    created_by, version
                                ) VALUES (
                                    :id, :tenantId, :number, :profileId, :customerId, :companyId,
                                    :templateId, :templateVersionId, :workflowVersionId,
                                    TIMESTAMPTZ '2026-07-01 00:00:00+00', TIMESTAMPTZ '2026-08-01 00:00:00+00',
                                    DATE '2026-07-31', DATE '2026-08-07', 'Asia/Shanghai', 'zh-CN', 'CNY',
                                    '{}'::jsonb, '{}'::jsonb, 100, 100, 'FINALIZING', 1, now(), :actorId, 2
                                )
                                """)
                        .param("id", fixture.previewId()).param("tenantId", fixture.tenantId())
                        .param("number", "PRE_" + suffix).param("profileId", fixture.profileId())
                        .param("customerId", fixture.customerId()).param("companyId", fixture.companyId())
                        .param("templateId", fixture.templateId())
                        .param("templateVersionId", fixture.templateVersionId())
                        .param("workflowVersionId", workflowVersionId)
                        .param("actorId", fixture.actorId()).update();
                local.sql("""
                                INSERT INTO approval_instances(
                                    id, tenant_id, invoice_preview_id, workflow_version_id,
                                    preview_version, approval_revision, status, requested_by, completed_at
                                ) VALUES (
                                    :id, :tenantId, :previewId, :workflowVersionId,
                                    1, 1, 'APPROVED', :actorId, now()
                                )
                                """)
                .param("id", fixture.approvalInstanceId()).param("tenantId", fixture.tenantId())
                        .param("previewId", fixture.previewId()).param("workflowVersionId", workflowVersionId)
                        .param("actorId", fixture.actorId()).update();
                connection.commit();
            } catch (RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static int insertInvoice(Fixture fixture, UUID invoiceId, InitialState state) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO invoices(
                         id, tenant_id, invoice_number, source_preview_id, invoice_profile_id,
                         customer_id, company_id, template_id, template_version_id, approval_instance_id,
                         period_start, period_end, issue_date, due_date, timezone, language, currency_code,
                         subtotal_minor, discount_minor, tax_minor, adjustment_minor, total_minor,
                         party_snapshot_json, profile_snapshot_json, render_model_json, data_snapshot_hash,
                         document_status, send_status, payment_status, finalized_by,
                         confirmed_at, sent_at, voided_at, paid_at, version
                     ) VALUES (
                         ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                         TIMESTAMPTZ '2026-07-01 00:00:00+00', TIMESTAMPTZ '2026-08-01 00:00:00+00',
                         DATE '2026-07-31', DATE '2026-08-07', 'Asia/Shanghai', 'zh-CN', 'CNY',
                         100, 0, 0, 0, 100, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, repeat('0', 64),
                         ?, ?, ?, ?, ?, ?, ?, ?, ?
                     )
                     """)) {
            statement.setObject(1, invoiceId);
            statement.setObject(2, fixture.tenantId());
            statement.setString(3, "INV_" + compact(invoiceId));
            statement.setObject(4, fixture.previewId());
            statement.setObject(5, fixture.profileId());
            statement.setObject(6, fixture.customerId());
            statement.setObject(7, fixture.companyId());
            statement.setObject(8, fixture.templateId());
            statement.setObject(9, fixture.templateVersionId());
            statement.setObject(10, fixture.approvalInstanceId());
            statement.setString(11, state.documentStatus());
            statement.setString(12, state.sendStatus());
            statement.setString(13, state.paymentStatus());
            statement.setObject(14, fixture.actorId());
            statement.setObject(15, state.confirmedAt());
            statement.setObject(16, state.sentAt());
            statement.setObject(17, state.voidedAt());
            statement.setObject(18, state.paidAt());
            statement.setLong(19, state.version());
            return statement.executeUpdate();
        }
    }

    private static UUID confirmedInvoice(Fixture fixture) throws Exception {
        UUID invoiceId = UUID.randomUUID();
        assertThat(insertInvoice(fixture, invoiceId, InitialState.valid())).isEqualTo(1);
        insertPdfEvidence(fixture, invoiceId);
        assertThat(updateInvoice(invoiceId, """
                document_status = 'CONFIRMED', confirmed_at = clock_timestamp(),
                updated_at = clock_timestamp(), version = version + 1
                """)).isEqualTo(1);
        return invoiceId;
    }

    private static UUID insertPdfEvidence(Fixture fixture, UUID invoiceId) {
        UUID fileId = UUID.randomUUID();
        String sha256 = "b".repeat(64);
        jdbc.sql("""
                        INSERT INTO files(
                            id, tenant_id, storage_provider, bucket_name, object_key,
                            original_filename, mime_type, file_size, sha256, created_by
                        ) VALUES (
                            :id, :tenantId, 'MINIO', 'invoice-guard', :objectKey,
                            'invoice.pdf', 'application/pdf', 128, :sha256, :actorId
                        )
                        """)
                .param("id", fileId).param("tenantId", fixture.tenantId())
                .param("objectKey", fileId.toString()).param("sha256", sha256)
                .param("actorId", fixture.actorId()).update();
        jdbc.sql("""
                        INSERT INTO invoice_files(
                            id, tenant_id, invoice_id, file_id, file_role, template_version_id,
                            renderer_version, chromium_version, content_sha256
                        ) VALUES (
                            :id, :tenantId, :invoiceId, :fileId, 'PDF', :templateVersionId,
                            'auto-invoice-worker/test', 'Chromium test', :sha256
                        )
                        """)
                .param("id", UUID.randomUUID()).param("tenantId", fixture.tenantId())
                .param("invoiceId", invoiceId).param("fileId", fileId)
                .param("templateVersionId", fixture.templateVersionId()).param("sha256", sha256).update();
        return fileId;
    }

    private static int replicaUpdate(String sql, Object... parameters) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var role = connection.createStatement()) {
                role.execute("SET LOCAL session_replication_role = replica");
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int index = 0; index < parameters.length; index++) {
                    statement.setObject(index + 1, parameters[index]);
                }
                int changed = statement.executeUpdate();
                connection.commit();
                return changed;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static UUID insertPayment(Fixture fixture, String label) {
        UUID paymentId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO payments(
                            id, tenant_id, payment_number, customer_id, company_id, currency_code,
                            amount_minor, payment_method, source_system, paid_at, status, created_by
                        ) VALUES (
                            :id, :tenantId, :number, :customerId, :companyId, 'CNY',
                            100, 'BANK_TRANSFER', 'TEST', clock_timestamp(), 'CONFIRMED', :actorId
                        )
                        """)
                .param("id", paymentId).param("tenantId", fixture.tenantId())
                .param("number", "PAY_" + label + "_" + compact(paymentId))
                .param("customerId", fixture.customerId()).param("companyId", fixture.companyId())
                .param("actorId", fixture.actorId()).update();
        return paymentId;
    }

    private static int insertAllocation(
            Fixture fixture, UUID paymentId, UUID invoiceId, UUID allocationId, long amountMinor) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO payment_allocations(
                         id, tenant_id, payment_id, invoice_id, amount_minor, status, allocated_by
                     ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)
                     """)) {
            statement.setObject(1, allocationId);
            statement.setObject(2, fixture.tenantId());
            statement.setObject(3, paymentId);
            statement.setObject(4, invoiceId);
            statement.setLong(5, amountMinor);
            statement.setObject(6, fixture.actorId());
            return statement.executeUpdate();
        }
    }

    private static int updateInvoice(UUID invoiceId, String assignments) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return updateInvoice(connection, invoiceId, assignments);
        }
    }

    private static int updateInvoice(Connection connection, UUID invoiceId, String assignments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE invoices SET " + assignments + " WHERE id = ?")) {
            statement.setObject(1, invoiceId);
            return statement.executeUpdate();
        }
    }

    private static void assertSqlState(String expected, SqlOperation operation) {
        assertThatThrownBy(operation::execute)
                .isInstanceOf(SQLException.class)
                .satisfies(exception -> assertThat(((SQLException) exception).getSQLState()).isEqualTo(expected));
    }

    private static int invoiceCount(UUID invoiceId) {
        return jdbc.sql("SELECT count(*) FROM invoices WHERE id = :id")
                .param("id", invoiceId).query(Integer.class).single();
    }

    private static String compact(UUID id) {
        return id.toString().replace("-", "");
    }

    private record Fixture(
            UUID tenantId, UUID actorId, UUID customerId, UUID companyId,
            UUID templateId, UUID templateVersionId, UUID profileId,
            UUID previewId, UUID approvalInstanceId) {
    }

    private record InitialState(
            String documentStatus, String sendStatus, String paymentStatus, long version,
            OffsetDateTime confirmedAt, OffsetDateTime sentAt, OffsetDateTime voidedAt, OffsetDateTime paidAt) {
        private static InitialState valid() {
            return new InitialState("FINALIZING", "NOT_QUEUED", "UNPAID", 0,
                    null, null, null, null);
        }
    }

    private record SqlResult(boolean committed, String sqlState) {
    }

    @FunctionalInterface
    private interface SqlOperation {
        void execute() throws Exception;
    }
}
