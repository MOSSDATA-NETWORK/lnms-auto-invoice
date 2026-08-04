package com.autoinvoice.api.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class FrozenFileReferenceConcurrencyIntegrationTest {
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
    void templatePublishWaitsForConcurrentAssetFileMetadataUpdate() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        insertTenant(tenantId);
        insertFile(tenantId, fileId, "template-before.png");
        jdbc.sql("""
                        INSERT INTO invoice_templates(id, tenant_id, template_code, template_name)
                        VALUES (:id, :tenantId, :code, 'Concurrency template')
                        """)
                .param("id", templateId).param("tenantId", tenantId)
                .param("code", "TPL_" + compact(templateId)).update();
        jdbc.sql("""
                        INSERT INTO invoice_template_versions(
                            id, tenant_id, template_id, version_no, html_content, schema_json,
                            content_sha256, status
                        ) VALUES (
                            :id, :tenantId, :templateId, 1, '<html></html>', '{}'::jsonb,
                            repeat('a', 64), 'DRAFT'
                        )
                        """)
                .param("id", versionId).param("tenantId", tenantId).param("templateId", templateId).update();
        jdbc.sql("""
                        INSERT INTO invoice_template_assets(
                            id, tenant_id, template_version_id, asset_key, file_id, usage_type
                        ) VALUES (:id, :tenantId, :versionId, 'logo', :fileId, 'LOGO')
                        """)
                .param("id", UUID.randomUUID()).param("tenantId", tenantId)
                .param("versionId", versionId).param("fileId", fileId).update();

        try (Connection fileUpdater = dataSource.getConnection();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            fileUpdater.setAutoCommit(false);
            updateFilename(fileUpdater, tenantId, fileId, "template-update-in-flight.png");

            CountDownLatch statementReady = new CountDownLatch(1);
            Future<Integer> publish = executor.submit(() -> executeAndCommit(
                    """
                    UPDATE invoice_template_versions
                    SET status = 'PUBLISHED', published_at = now()
                    WHERE tenant_id = ? AND id = ?
                    """, tenantId, versionId, statementReady));

            assertThat(statementReady.await(5, TimeUnit.SECONDS)).isTrue();
            assertBlocked(publish);
            fileUpdater.commit();
            assertThat(publish.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }

        assertThat(filename(tenantId, fileId)).isEqualTo("template-update-in-flight.png");
        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE files SET original_filename = 'template-after-publish.png'
                        WHERE tenant_id = :tenantId AND id = :fileId
                        """)
                .param("tenantId", tenantId).param("fileId", fileId).update())
                .isInstanceOf(Exception.class);
    }

    @Test
    void usageFinalTransitionWaitsForConcurrentEvidenceFileMetadataUpdate() throws Exception {
        UsageFixture fixture = insertUsageFixture();

        try (Connection fileUpdater = dataSource.getConnection();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            fileUpdater.setAutoCommit(false);
            updateFilename(fileUpdater, fixture.tenantId(), fixture.fileId(), "usage-update-in-flight.json");

            CountDownLatch statementReady = new CountDownLatch(1);
            Future<Integer> finalizeSnapshot = executor.submit(() -> executeAndCommit(
                    """
                    UPDATE usage_snapshots
                    SET snapshot_kind = 'FINAL'
                    WHERE tenant_id = ? AND id = ?
                    """, fixture.tenantId(), fixture.snapshotId(), statementReady));

            assertThat(statementReady.await(5, TimeUnit.SECONDS)).isTrue();
            assertBlocked(finalizeSnapshot);
            fileUpdater.commit();
            assertThat(finalizeSnapshot.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }

        assertThat(filename(fixture.tenantId(), fixture.fileId())).isEqualTo("usage-update-in-flight.json");
        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE files SET original_filename = 'usage-after-final.json'
                        WHERE tenant_id = :tenantId AND id = :fileId
                        """)
                .param("tenantId", fixture.tenantId()).param("fileId", fixture.fileId()).update())
                .isInstanceOf(Exception.class);
    }

    @Test
    void formalInvoiceItemUsageReferenceWaitsForEvidenceFileUpdateAndThenFreezesIt() throws Exception {
        UsageFixture usage = insertUsageFixture();
        FormalInvoiceFixture invoice = insertFormalInvoice(usage.tenantId());
        UUID sourcePreviewItemId = insertPreviewUsageItem(
                usage.tenantId(), invoice.previewId(), usage.snapshotId());
        FormalUsageFixture fixture = new FormalUsageFixture(
                usage.tenantId(), invoice.invoiceId(), usage.snapshotId(), usage.fileId(), sourcePreviewItemId);

        try (Connection fileUpdater = dataSource.getConnection();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            fileUpdater.setAutoCommit(false);
            updateFilename(fileUpdater, fixture.tenantId(), fixture.fileId(), "formal-usage-update-in-flight.json");

            CountDownLatch statementReady = new CountDownLatch(1);
            Future<Integer> insertItem = executor.submit(() -> insertFormalUsageItemAndCommit(fixture, statementReady));

            assertThat(statementReady.await(5, TimeUnit.SECONDS)).isTrue();
            assertBlocked(insertItem);
            fileUpdater.commit();
            assertThat(insertItem.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }

        assertThat(filename(fixture.tenantId(), fixture.fileId()))
                .isEqualTo("formal-usage-update-in-flight.json");
        assertThatThrownBy(() -> updateFileWithFreshConnection(
                fixture.tenantId(), fixture.fileId(), "formal-usage-after-reference.json"))
                .isInstanceOf(java.sql.SQLException.class)
                .satisfies(exception -> assertThat(((java.sql.SQLException) exception).getSQLState())
                        .isEqualTo("55000"));
    }

    @Test
    void reverseUsageEvidenceLinkMovesUseStableSnapshotThenFileLockOrder() throws Exception {
        UsageSwapFixture fixture = insertUsageSwapFixture();
        String firstApplication = "usage-link-swap-a-" + compact(UUID.randomUUID());
        String secondApplication = "usage-link-swap-b-" + compact(UUID.randomUUID());

        try (ExecutorService executor = Executors.newFixedThreadPool(2);
             Connection firstFileGate = dataSource.getConnection();
             Connection secondFileGate = dataSource.getConnection()) {
            firstFileGate.setAutoCommit(false);
            secondFileGate.setAutoCommit(false);
            lockFile(firstFileGate, fixture.tenantId(), fixture.firstFileId());
            lockFile(secondFileGate, fixture.tenantId(), fixture.secondFileId());

            CountDownLatch statementsReady = new CountDownLatch(2);
            Future<Integer> firstMove = executor.submit(() -> updateUsageLinkAndCommit(
                    firstApplication, fixture.tenantId(),
                    fixture.firstSnapshotId(), fixture.firstFileId(), "RAW_RESPONSE",
                    fixture.secondSnapshotId(), fixture.secondFileId(), statementsReady));
            Future<Integer> secondMove = executor.submit(() -> updateUsageLinkAndCommit(
                    secondApplication, fixture.tenantId(),
                    fixture.secondSnapshotId(), fixture.secondFileId(), "GRAPH_BITS",
                    fixture.firstSnapshotId(), fixture.firstFileId(), statementsReady));

            assertThat(statementsReady.await(5, TimeUnit.SECONDS)).isTrue();
            awaitLockWaiters(firstApplication, secondApplication);
            firstFileGate.commit();
            secondFileGate.commit();

            assertThat(firstMove.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(secondMove.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }

        assertThat(jdbc.sql("""
                        SELECT count(*)
                        FROM usage_snapshot_files
                        WHERE tenant_id = :tenantId
                          AND (
                              (usage_snapshot_id = :secondSnapshotId
                               AND file_id = :secondFileId AND file_role = 'RAW_RESPONSE')
                              OR
                              (usage_snapshot_id = :firstSnapshotId
                               AND file_id = :firstFileId AND file_role = 'GRAPH_BITS')
                          )
                        """)
                .param("tenantId", fixture.tenantId())
                .param("firstSnapshotId", fixture.firstSnapshotId())
                .param("firstFileId", fixture.firstFileId())
                .param("secondSnapshotId", fixture.secondSnapshotId())
                .param("secondFileId", fixture.secondFileId())
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void invoiceFileReferenceWaitsForConcurrentFileMetadataUpdateAndThenFreezesIt() throws Exception {
        FormalFileFixture fixture = insertFormalFileFixture("invoice-file");

        try (ExecutorService executor = Executors.newSingleThreadExecutor();
             Connection fileUpdater = dataSource.getConnection()) {
            fileUpdater.setAutoCommit(false);
            updateFilename(fileUpdater, fixture.tenantId(), fixture.fileId(), "invoice-update-in-flight.pdf");

            CountDownLatch statementReady = new CountDownLatch(1);
            Future<Integer> insert = executor.submit(() -> insertInvoiceFileAndCommit(fixture, statementReady));

            assertThat(statementReady.await(5, TimeUnit.SECONDS)).isTrue();
            assertBlocked(insert);
            fileUpdater.commit();
            assertThat(insert.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }

        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE files SET original_filename = 'invoice-after-reference.pdf'
                        WHERE tenant_id = :tenantId AND id = :fileId
                        """)
                .param("tenantId", fixture.tenantId()).param("fileId", fixture.fileId()).update())
                .isInstanceOf(Exception.class);
    }

    @Test
    void formalAdjustmentReferenceWaitsForConcurrentFileMetadataUpdateAndThenFreezesIt() throws Exception {
        FormalFileFixture fixture = insertFormalFileFixture("invoice-adjustment");

        try (ExecutorService executor = Executors.newSingleThreadExecutor();
             Connection fileUpdater = dataSource.getConnection()) {
            fileUpdater.setAutoCommit(false);
            updateFilename(fileUpdater, fixture.tenantId(), fixture.fileId(), "adjustment-update-in-flight.pdf");

            CountDownLatch statementReady = new CountDownLatch(1);
            Future<Integer> insert = executor.submit(() -> insertInvoiceAdjustmentAndCommit(fixture, statementReady));

            assertThat(statementReady.await(5, TimeUnit.SECONDS)).isTrue();
            assertBlocked(insert);
            fileUpdater.commit();
            assertThat(insert.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }

        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE files SET original_filename = 'adjustment-after-reference.pdf'
                        WHERE tenant_id = :tenantId AND id = :fileId
                        """)
                .param("tenantId", fixture.tenantId()).param("fileId", fixture.fileId()).update())
                .isInstanceOf(Exception.class);
    }

    @Test
    void directFrozenAttachmentReferenceBlocksAndRejectsConcurrentFileMetadataUpdate() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        insertTenant(tenantId);
        jdbc.sql("INSERT INTO customers(id, tenant_id, customer_no, customer_name) VALUES (:id, :tenantId, :no, 'Attachment customer')")
                .param("id", customerId).param("tenantId", tenantId)
                .param("no", "C_" + compact(customerId)).update();
        insertFile(tenantId, fileId, "payment-before.pdf");

        try (Connection paymentInserter = dataSource.getConnection();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            paymentInserter.setAutoCommit(false);
            try (PreparedStatement statement = paymentInserter.prepareStatement("""
                    INSERT INTO payments(
                        id, tenant_id, payment_number, customer_id, currency_code, amount_minor,
                        payment_method, source_system, paid_at, status, attachment_file_id
                    ) VALUES (?, ?, ?, ?, 'CNY', 100, 'BANK_TRANSFER', 'TEST', now(), 'CONFIRMED', ?)
                    """)) {
                statement.setObject(1, paymentId);
                statement.setObject(2, tenantId);
                statement.setString(3, "PAY-" + compact(paymentId));
                statement.setObject(4, customerId);
                statement.setObject(5, fileId);
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }

            CountDownLatch statementReady = new CountDownLatch(1);
            Future<Integer> fileUpdate = executor.submit(() -> {
                try (Connection connection = dataSource.getConnection();
                     PreparedStatement statement = connection.prepareStatement("""
                             UPDATE files SET original_filename = 'payment-update-in-flight.pdf'
                             WHERE tenant_id = ? AND id = ?
                             """)) {
                    statement.setObject(1, tenantId);
                    statement.setObject(2, fileId);
                    statementReady.countDown();
                    return statement.executeUpdate();
                }
            });

            assertThat(statementReady.await(5, TimeUnit.SECONDS)).isTrue();
            assertBlocked(fileUpdate);
            paymentInserter.commit();
            assertThatThrownBy(() -> fileUpdate.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
        }

        assertThat(filename(tenantId, fileId)).isEqualTo("payment-before.pdf");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM pg_trigger
                        WHERE tgname IN (
                            'trg_invoice_files_lock_file_reference',
                            'trg_invoice_adjustments_lock_file_reference',
                            'trg_payments_lock_attachment_file'
                        ) AND NOT tgisinternal
                        """).query(Integer.class).single()).isEqualTo(3);
    }

    private static void assertBlocked(Future<?> future) {
        assertThatThrownBy(() -> future.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
    }

    private static int executeAndCommit(String sql, UUID tenantId, UUID entityId,
                                        CountDownLatch statementReady) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            statement.setObject(1, tenantId);
            statement.setObject(2, entityId);
            statementReady.countDown();
            try {
                int updated = statement.executeUpdate();
                connection.commit();
                return updated;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void updateFilename(Connection connection, UUID tenantId, UUID fileId, String filename)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE files SET original_filename = ? WHERE tenant_id = ? AND id = ?
                """)) {
            statement.setString(1, filename);
            statement.setObject(2, tenantId);
            statement.setObject(3, fileId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static void lockFile(Connection connection, UUID tenantId, UUID fileId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM files WHERE tenant_id = ? AND id = ? FOR UPDATE
                """)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, fileId);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
            }
        }
    }

    private static int updateUsageLinkAndCommit(
            String applicationName, UUID tenantId,
            UUID oldSnapshotId, UUID oldFileId, String fileRole,
            UUID newSnapshotId, UUID newFileId, CountDownLatch statementReady) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement config = connection.prepareStatement(
                    "SELECT set_config('application_name', ?, false)")) {
                config.setString(1, applicationName);
                config.execute();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE usage_snapshot_files
                    SET usage_snapshot_id = ?, file_id = ?
                    WHERE tenant_id = ? AND usage_snapshot_id = ? AND file_id = ? AND file_role = ?
                    """)) {
                statement.setObject(1, newSnapshotId);
                statement.setObject(2, newFileId);
                statement.setObject(3, tenantId);
                statement.setObject(4, oldSnapshotId);
                statement.setObject(5, oldFileId);
                statement.setString(6, fileRole);
                statementReady.countDown();
                try {
                    int updated = statement.executeUpdate();
                    connection.commit();
                    return updated;
                } catch (Exception exception) {
                    connection.rollback();
                    throw exception;
                }
            }
        }
    }

    private static void awaitLockWaiters(String firstApplication, String secondApplication) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int waiters = 0;
        while (System.nanoTime() < deadline) {
            waiters = jdbc.sql("""
                            SELECT count(*)
                            FROM pg_stat_activity
                            WHERE application_name IN (:firstApplication, :secondApplication)
                              AND wait_event_type = 'Lock'
                            """)
                    .param("firstApplication", firstApplication)
                    .param("secondApplication", secondApplication)
                    .query(Integer.class).single();
            if (waiters == 2) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(waiters)
                .as("both reverse link updates should be waiting on the staged file/snapshot locks")
                .isEqualTo(2);
    }

    private static int insertInvoiceFileAndCommit(FormalFileFixture fixture, CountDownLatch statementReady)
            throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO invoice_files(
                         id, tenant_id, invoice_id, file_id, file_role, content_sha256
                     ) VALUES (?, ?, ?, ?, 'PDF', repeat('e', 64))
                     """)) {
            connection.setAutoCommit(false);
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, fixture.tenantId());
            statement.setObject(3, fixture.invoiceId());
            statement.setObject(4, fixture.fileId());
            statementReady.countDown();
            try {
                int inserted = statement.executeUpdate();
                connection.commit();
                return inserted;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static int insertInvoiceAdjustmentAndCommit(FormalFileFixture fixture, CountDownLatch statementReady)
            throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO invoice_adjustments(
                         id, tenant_id, invoice_id, source_preview_adjustment_id,
                         adjustment_type, description, amount_minor, tax_rate,
                         included_in_tax_base, reason, attachment_file_id,
                         operator_snapshot_json
                     )
                     SELECT ?, ?, ?, source.id,
                            source.adjustment_type, source.description, source.amount_minor,
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
            connection.setAutoCommit(false);
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, fixture.tenantId());
            statement.setObject(3, fixture.invoiceId());
            statement.setObject(4, fixture.tenantId());
            statement.setObject(5, fixture.sourcePreviewAdjustmentId());
            statementReady.countDown();
            try {
                int inserted = statement.executeUpdate();
                connection.commit();
                return inserted;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static int insertFormalUsageItemAndCommit(FormalUsageFixture fixture, CountDownLatch statementReady)
            throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO invoice_items(
                         id, tenant_id, invoice_id, source_preview_item_id,
                         contract_item_id, service_id, pricing_rule_version_id,
                         usage_snapshot_id, source_key, line_no, item_name, item_description,
                         billing_period_start, billing_period_end,
                         raw_usage, converted_usage, rounded_usage, billing_usage,
                         quantity, unit, unit_price, subtotal_minor, discount_minor,
                         tax_minor, total_minor, calculation_snapshot_json, display_json
                     )
                     SELECT ?, ?, ?, source.id,
                            source.contract_item_id, source.service_id,
                            source.pricing_rule_version_id, source.usage_snapshot_id,
                            source.source_key, source.line_no, source.item_name,
                            source.item_description, source.billing_period_start,
                            source.billing_period_end, source.raw_usage,
                            source.converted_usage, source.rounded_usage,
                            source.billing_usage, source.quantity, source.unit,
                            source.unit_price, source.subtotal_minor,
                            source.discount_minor, source.tax_minor, source.total_minor,
                            source.calculation_snapshot_json, source.display_json
                     FROM invoice_preview_items source
                     WHERE source.tenant_id = ? AND source.id = ?
                     """)) {
            connection.setAutoCommit(false);
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, fixture.tenantId());
            statement.setObject(3, fixture.invoiceId());
            statement.setObject(4, fixture.tenantId());
            statement.setObject(5, fixture.sourcePreviewItemId());
            statementReady.countDown();
            try {
                int inserted = statement.executeUpdate();
                connection.commit();
                return inserted;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static UsageFixture insertUsageFixture() {
        UUID tenantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        UUID pricingRuleId = UUID.randomUUID();
        UUID contractItemId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        insertTenant(tenantId);
        jdbc.sql("INSERT INTO customers(id, tenant_id, customer_no, customer_name) VALUES (:id, :tenantId, :no, 'Concurrency customer')")
                .param("id", customerId).param("tenantId", tenantId).param("no", "C_" + compact(customerId)).update();
        jdbc.sql("INSERT INTO companies(id, tenant_id, customer_id, company_code, company_name) VALUES (:id, :tenantId, :customerId, :code, 'Concurrency company')")
                .param("id", companyId).param("tenantId", tenantId).param("customerId", customerId)
                .param("code", "CO_" + compact(companyId)).update();
        jdbc.sql("""
                        INSERT INTO services(
                            id, tenant_id, service_no, customer_id, company_id, service_name, service_type
                        ) VALUES (:id, :tenantId, :no, :customerId, :companyId, 'Concurrency service', 'BANDWIDTH_95')
                        """)
                .param("id", serviceId).param("tenantId", tenantId).param("no", "S_" + compact(serviceId))
                .param("customerId", customerId).param("companyId", companyId).update();
        jdbc.sql("""
                        INSERT INTO contracts(
                            id, tenant_id, contract_no, customer_id, company_id, contract_name,
                            effective_from, currency_code
                        ) VALUES (:id, :tenantId, :no, :customerId, :companyId,
                                  'Concurrency contract', DATE '2026-01-01', 'CNY')
                        """)
                .param("id", contractId).param("tenantId", tenantId).param("no", "CT_" + compact(contractId))
                .param("customerId", customerId).param("companyId", companyId).update();
        jdbc.sql("INSERT INTO pricing_rules(id, tenant_id, rule_code, rule_name) VALUES (:id, :tenantId, :code, 'Concurrency rule')")
                .param("id", pricingRuleId).param("tenantId", tenantId)
                .param("code", "R_" + compact(pricingRuleId)).update();
        jdbc.sql("""
                        INSERT INTO contract_items(
                            id, tenant_id, contract_item_no, contract_id, service_id, pricing_rule_id,
                            item_name, billing_type, effective_from
                        ) VALUES (:id, :tenantId, :no, :contractId, :serviceId, :pricingRuleId,
                                  'Concurrency usage', 'BANDWIDTH_95', TIMESTAMPTZ '2026-01-01 00:00:00+00')
                        """)
                .param("id", contractItemId).param("tenantId", tenantId).param("no", "CI_" + compact(contractItemId))
                .param("contractId", contractId).param("serviceId", serviceId).param("pricingRuleId", pricingRuleId)
                .update();
        jdbc.sql("""
                        INSERT INTO usage_snapshots(
                            id, tenant_id, contract_item_id, snapshot_kind, period_start, period_end, data_hash
                        ) VALUES (:id, :tenantId, :contractItemId, 'PREVIEW',
                                  TIMESTAMPTZ '2026-07-01 00:00:00+00',
                                  TIMESTAMPTZ '2026-08-01 00:00:00+00', repeat('b', 64))
                        """)
                .param("id", snapshotId).param("tenantId", tenantId).param("contractItemId", contractItemId).update();
        insertFile(tenantId, fileId, "usage-before.json");
        jdbc.sql("""
                        INSERT INTO usage_snapshot_files(tenant_id, usage_snapshot_id, file_id, file_role)
                        VALUES (:tenantId, :snapshotId, :fileId, 'RAW_RESPONSE')
                        """)
                .param("tenantId", tenantId).param("snapshotId", snapshotId).param("fileId", fileId).update();
        return new UsageFixture(tenantId, snapshotId, fileId);
    }

    private static UsageSwapFixture insertUsageSwapFixture() {
        UsageFixture first = insertUsageFixture();
        UUID contractItemId = jdbc.sql("""
                        SELECT contract_item_id FROM usage_snapshots
                        WHERE tenant_id = :tenantId AND id = :snapshotId
                        """)
                .param("tenantId", first.tenantId()).param("snapshotId", first.snapshotId())
                .query(UUID.class).single();
        UUID secondSnapshotId = UUID.randomUUID();
        UUID secondFileId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO usage_snapshots(
                            id, tenant_id, contract_item_id, snapshot_kind, period_start, period_end, data_hash
                        ) VALUES (:id, :tenantId, :contractItemId, 'PREVIEW',
                                  TIMESTAMPTZ '2026-07-01 00:00:00+00',
                                  TIMESTAMPTZ '2026-08-01 00:00:00+00', repeat('d', 64))
                        """)
                .param("id", secondSnapshotId).param("tenantId", first.tenantId())
                .param("contractItemId", contractItemId).update();
        insertFile(first.tenantId(), secondFileId, "usage-second.json");
        jdbc.sql("""
                        INSERT INTO usage_snapshot_files(tenant_id, usage_snapshot_id, file_id, file_role)
                        VALUES (:tenantId, :snapshotId, :fileId, 'GRAPH_BITS')
                        """)
                .param("tenantId", first.tenantId()).param("snapshotId", secondSnapshotId)
                .param("fileId", secondFileId).update();
        return new UsageSwapFixture(
                first.tenantId(), first.snapshotId(), first.fileId(), secondSnapshotId, secondFileId);
    }

    private static UUID insertPreviewUsageItem(UUID tenantId, UUID previewId, UUID snapshotId)
            throws Exception {
        UUID itemId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var role = connection.createStatement()) {
                role.execute("SET LOCAL session_replication_role = replica");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO invoice_preview_items(
                        id, tenant_id, invoice_preview_id, usage_snapshot_id,
                        source_key, line_no, item_name,
                        billing_period_start, billing_period_end,
                        subtotal_minor, discount_minor, tax_minor, total_minor,
                        calculation_snapshot_json
                    ) VALUES (
                        ?, ?, ?, ?, 'usage-concurrency', 1,
                        'Usage evidence concurrency',
                        TIMESTAMPTZ '2026-07-01 00:00:00+00',
                        TIMESTAMPTZ '2026-08-01 00:00:00+00',
                        100, 0, 0, 100, '{}'::jsonb
                    )
                    """)) {
                statement.setObject(1, itemId);
                statement.setObject(2, tenantId);
                statement.setObject(3, previewId);
                statement.setObject(4, snapshotId);
                statement.executeUpdate();
            }
            connection.commit();
        }
        return itemId;
    }

    private static UUID insertPreviewAdjustment(UUID tenantId, UUID previewId, UUID fileId)
            throws Exception {
        UUID adjustmentId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var role = connection.createStatement()) {
                role.execute("SET LOCAL session_replication_role = replica");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO invoice_preview_adjustments(
                        id, tenant_id, invoice_preview_id, adjustment_type,
                        description, amount_minor, included_in_tax_base, reason,
                        attachment_file_id, created_by
                    ) VALUES (
                        ?, ?, ?, 'CUSTOM', 'Concurrency adjustment',
                        1, false, 'Concurrency regression', ?, ?
                    )
                    """)) {
                statement.setObject(1, adjustmentId);
                statement.setObject(2, tenantId);
                statement.setObject(3, previewId);
                statement.setObject(4, fileId);
                statement.setObject(5, UUID.randomUUID());
                statement.executeUpdate();
            }
            connection.commit();
        }
        return adjustmentId;
    }

    private static FormalFileFixture insertFormalFileFixture(String label) throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        insertTenant(tenantId);
        insertFile(tenantId, fileId, label + "-before.pdf");
        FormalInvoiceFixture invoice = insertFormalInvoice(tenantId);
        UUID sourcePreviewAdjustmentId = insertPreviewAdjustment(
                tenantId, invoice.previewId(), fileId);

        return new FormalFileFixture(
                tenantId, invoice.invoiceId(), fileId, sourcePreviewAdjustmentId);
    }

    private static FormalInvoiceFixture insertFormalInvoice(UUID tenantId) throws Exception {
        UUID invoiceId = UUID.randomUUID();
        UUID previewId = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var role = connection.createStatement()) {
                role.execute("SET LOCAL session_replication_role = replica");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO invoices(
                        id, tenant_id, invoice_number, source_preview_id, invoice_profile_id,
                        customer_id, company_id, template_id, template_version_id, approval_instance_id,
                        period_start, period_end, issue_date, due_date, timezone, language, currency_code,
                        subtotal_minor, discount_minor, tax_minor, adjustment_minor, total_minor,
                        party_snapshot_json, profile_snapshot_json, render_model_json, data_snapshot_hash,
                        document_status, send_status, payment_status, finalized_by
                    ) VALUES (
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        now() - interval '1 month', now(), current_date - 30, current_date + 7,
                        'Asia/Shanghai', 'zh-CN', 'CNY',
                        100, 0, 0, 0, 100,
                        '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, repeat('0', 64),
                        'FINALIZING', 'NOT_QUEUED', 'UNPAID', ?
                    )
                    """)) {
                statement.setObject(1, invoiceId);
                statement.setObject(2, tenantId);
                statement.setString(3, "INV-" + compact(invoiceId));
                statement.setObject(4, previewId);
                for (int index = 5; index <= 10; index++) {
                    statement.setObject(index, UUID.randomUUID());
                }
                statement.setObject(11, UUID.randomUUID());
                statement.executeUpdate();
            }
            connection.commit();
        }
        return new FormalInvoiceFixture(invoiceId, previewId);
    }

    private static void insertTenant(UUID tenantId) {
        jdbc.sql("INSERT INTO tenants(id, tenant_code, tenant_name) VALUES (:id, :code, 'Concurrency tenant')")
                .param("id", tenantId).param("code", "T_" + compact(tenantId)).update();
    }

    private static void insertFile(UUID tenantId, UUID fileId, String filename) {
        jdbc.sql("""
                        INSERT INTO files(
                            id, tenant_id, storage_provider, bucket_name, object_key,
                            original_filename, mime_type, file_size, sha256
                        ) VALUES (
                            :id, :tenantId, 'MINIO', 'concurrency', :objectKey,
                            :filename, 'application/octet-stream', 1, repeat('c', 64)
                        )
                        """)
                .param("id", fileId).param("tenantId", tenantId).param("objectKey", fileId.toString())
                .param("filename", filename).update();
    }

    private static int updateFileWithFreshConnection(UUID tenantId, UUID fileId, String filename)
            throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE files SET original_filename = ? WHERE tenant_id = ? AND id = ?
                     """)) {
            statement.setString(1, filename);
            statement.setObject(2, tenantId);
            statement.setObject(3, fileId);
            return statement.executeUpdate();
        }
    }

    private static String filename(UUID tenantId, UUID fileId) {
        return jdbc.sql("SELECT original_filename FROM files WHERE tenant_id = :tenantId AND id = :fileId")
                .param("tenantId", tenantId).param("fileId", fileId).query(String.class).single();
    }

    private static String compact(UUID id) {
        return id.toString().replace("-", "").substring(0, 20).toUpperCase();
    }

    private record UsageFixture(UUID tenantId, UUID snapshotId, UUID fileId) {
    }

    private record UsageSwapFixture(
            UUID tenantId,
            UUID firstSnapshotId,
            UUID firstFileId,
            UUID secondSnapshotId,
            UUID secondFileId) {
    }

    private record FormalInvoiceFixture(UUID invoiceId, UUID previewId) {
    }

    private record FormalFileFixture(
            UUID tenantId, UUID invoiceId, UUID fileId, UUID sourcePreviewAdjustmentId) {
    }

    private record FormalUsageFixture(
            UUID tenantId, UUID invoiceId, UUID snapshotId, UUID fileId, UUID sourcePreviewItemId) {
    }
}
