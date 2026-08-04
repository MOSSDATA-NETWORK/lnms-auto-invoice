package com.autoinvoice.payment;

import com.autoinvoice.platform.numbering.NumberSequenceService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PaymentDatabaseIntegrityIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.9-alpine");

    private static SingleConnectionDataSource dataSource;
    private static JdbcClient jdbc;
    private static PaymentService payments;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource migrationDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(migrationDataSource).locations("classpath:db/migration").load().migrate();

        dataSource = new SingleConnectionDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
        jdbc = JdbcClient.create(dataSource);
        payments = new PaymentService(jdbc, new NumberSequenceService(jdbc));
        transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
    }

    @AfterAll
    static void closeConnection() {
        dataSource.destroy();
    }

    @Test
    void legalAllocationRefundAndReversalDeriveStatusAndAdvanceVersionExactlyOnce() {
        Fixture fixture = fixture("legal");

        PaymentService.PaymentResult payment = transactions.execute(status -> payments.record(
                fixture.tenantId(), fixture.actorId(), new PaymentService.RecordPayment(
                        fixture.customerId(), fixture.companyId(), "CNY", 100,
                        "BANK_TRANSFER", "TEST", null, OffsetDateTime.now(), null)));

        PaymentService.AllocationResult allocation = transactions.execute(status -> payments.allocate(
                fixture.tenantId(), fixture.actorId(), payment.paymentId(),
                new PaymentService.AllocatePayment(fixture.invoiceId(), 40, 0)));

        assertThat(allocation.paymentStatus()).isEqualTo("PARTIALLY_ALLOCATED");
        assertThat(allocation.invoicePaymentStatus()).isEqualTo("PARTIALLY_PAID");
        assertPayment(payment.paymentId(), "PARTIALLY_ALLOCATED", 1);
        assertInvoicePayment(fixture.invoiceId(), "PARTIALLY_PAID", 1, false);

        PaymentService.RefundResult refund = transactions.execute(status -> payments.refund(
                fixture.tenantId(), fixture.actorId(), payment.paymentId(), 1,
                20, OffsetDateTime.now(), null, "customer refund"));

        assertThat(refund.paymentStatus()).isEqualTo("PARTIALLY_REFUNDED");
        assertPayment(payment.paymentId(), "PARTIALLY_REFUNDED", 2);
        assertInvoicePayment(fixture.invoiceId(), "PARTIALLY_PAID", 1, false);

        PaymentService.AllocationResult reversal = transactions.execute(status -> payments.reverseAllocation(
                fixture.tenantId(), fixture.actorId(), payment.paymentId(), allocation.allocationId(),
                2, "allocation correction"));

        assertThat(reversal.paymentStatus()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(reversal.invoicePaymentStatus()).isEqualTo("UNPAID");
        assertPayment(payment.paymentId(), "PARTIALLY_REFUNDED", 3);
        assertInvoicePayment(fixture.invoiceId(), "UNPAID", 2, false);
    }

    @Test
    void directSqlAllocationAndReversalRefreshInvoiceStatusPaidAtAndVersion() {
        Fixture fixture = fixture("invoice-derived");
        setInvoiceDueDateWithReplica(fixture.invoiceId(), -1);
        PaymentService.PaymentResult payment = transactions.execute(status -> payments.record(
                fixture.tenantId(), fixture.actorId(), new PaymentService.RecordPayment(
                        fixture.customerId(), fixture.companyId(), "CNY", 100,
                        "BANK_TRANSFER", "TEST", null, OffsetDateTime.now(), null)));
        UUID allocationId = UUID.randomUUID();

        assertThat(jdbc.sql("""
                        INSERT INTO payment_allocations(
                            id, tenant_id, payment_id, invoice_id, amount_minor, status, allocated_by
                        ) VALUES (:id, :tenantId, :paymentId, :invoiceId, 100, 'ACTIVE', :actorId)
                        """)
                .param("id", allocationId).param("tenantId", fixture.tenantId())
                .param("paymentId", payment.paymentId()).param("invoiceId", fixture.invoiceId())
                .param("actorId", fixture.actorId()).update()).isEqualTo(1);
        assertInvoicePayment(fixture.invoiceId(), "PAID", 1, true);

        assertThat(jdbc.sql("""
                        UPDATE payment_allocations
                        SET status = 'REVERSED', reversed_by = :actorId,
                            reversed_at = clock_timestamp(), reversal_reason = 'direct correction'
                        WHERE tenant_id = :tenantId AND id = :allocationId
                        """)
                .param("actorId", fixture.actorId()).param("tenantId", fixture.tenantId())
                .param("allocationId", allocationId).update()).isEqualTo(1);
        assertInvoicePayment(fixture.invoiceId(), "OVERDUE", 2, false);
    }

    @Test
    void rejectsDirectStatusBypassAndBlankAllocationReversalReason() {
        Fixture fixture = fixture("guards");

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO payments(
                            id, tenant_id, payment_number, customer_id, company_id, currency_code,
                            amount_minor, payment_method, source_system, paid_at, status, created_by
                        ) VALUES (
                            :id, :tenantId, :number, :customerId, :companyId, 'CNY',
                            100, 'BANK_TRANSFER', 'TEST', now(), 'ALLOCATED', :actorId
                        )
                        """)
                .param("id", UUID.randomUUID()).param("tenantId", fixture.tenantId())
                .param("number", "PAY-BYPASS-" + fixture.tenantId())
                .param("customerId", fixture.customerId()).param("companyId", fixture.companyId())
                .param("actorId", fixture.actorId()).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        PaymentService.PaymentResult payment = transactions.execute(status -> payments.record(
                fixture.tenantId(), fixture.actorId(), new PaymentService.RecordPayment(
                        fixture.customerId(), fixture.companyId(), "CNY", 100,
                        "BANK_TRANSFER", "TEST", null, OffsetDateTime.now(), null)));
        PaymentService.AllocationResult allocation = transactions.execute(status -> payments.allocate(
                fixture.tenantId(), fixture.actorId(), payment.paymentId(),
                new PaymentService.AllocatePayment(fixture.invoiceId(), 40, 0)));

        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE payments
                        SET status = 'REFUNDED', updated_at = now(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :paymentId
                        """)
                .param("tenantId", fixture.tenantId()).param("paymentId", payment.paymentId()).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE payment_allocations
                        SET status = 'REVERSED', reversed_by = :actorId,
                            reversed_at = now(), reversal_reason = '   '
                        WHERE tenant_id = :tenantId AND id = :allocationId
                        """)
                .param("tenantId", fixture.tenantId()).param("actorId", fixture.actorId())
                .param("allocationId", allocation.allocationId()).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        assertPayment(payment.paymentId(), "PARTIALLY_ALLOCATED", 1);
    }

    @Test
    void rejectsAllocationCreatedAsAlreadyReversedWithoutChangingPayment() {
        Fixture fixture = fixture("initial-reversed");
        PaymentService.PaymentResult payment = transactions.execute(status -> payments.record(
                fixture.tenantId(), fixture.actorId(), new PaymentService.RecordPayment(
                        fixture.customerId(), fixture.companyId(), "CNY", 100,
                        "BANK_TRANSFER", "TEST", null, OffsetDateTime.now(), null)));
        UUID fabricatedAllocationId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO payment_allocations(
                            id, tenant_id, payment_id, invoice_id, amount_minor, status,
                            allocated_by, reversed_by, reversed_at, reversal_reason
                        ) VALUES (
                            :id, :tenantId, :paymentId, :invoiceId, 40, 'REVERSED',
                            :actorId, :actorId, now(), 'fabricated reversal'
                        )
                        """)
                .param("id", fabricatedAllocationId).param("tenantId", fixture.tenantId())
                .param("paymentId", payment.paymentId()).param("invoiceId", fixture.invoiceId())
                .param("actorId", fixture.actorId()).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(exception -> assertThat(findSqlState(exception)).isEqualTo("23514"));

        assertThat(jdbc.sql("SELECT count(*) FROM payment_allocations WHERE id = :id")
                .param("id", fabricatedAllocationId)
                .query(Integer.class).single()).isZero();
        assertPayment(payment.paymentId(), "CONFIRMED", 0);
    }

    @Test
    void refundUpdateWithUnchangedStatusDoesNotAdvancePaymentVersion() {
        Fixture fixture = fixture("refund-noop");

        PaymentService.PaymentResult payment = transactions.execute(status -> payments.record(
                fixture.tenantId(), fixture.actorId(), new PaymentService.RecordPayment(
                        fixture.customerId(), fixture.companyId(), "CNY", 100,
                        "BANK_TRANSFER", "TEST", null, OffsetDateTime.now(), null)));
        PaymentService.RefundResult refund = transactions.execute(status -> payments.refund(
                fixture.tenantId(), fixture.actorId(), payment.paymentId(), 0,
                20, OffsetDateTime.now(), null, "customer refund"));

        assertPayment(payment.paymentId(), "PARTIALLY_REFUNDED", 1);
        assertThat(jdbc.sql("""
                        UPDATE payment_refunds
                        SET status = status
                        WHERE tenant_id = :tenantId AND id = :refundId
                        """)
                .param("tenantId", fixture.tenantId()).param("refundId", refund.refundId()).update())
                .isEqualTo(1);
        assertPayment(payment.paymentId(), "PARTIALLY_REFUNDED", 1);
    }

    @Test
    void temporaryPaymentTableCannotShadowThePublicBalanceGuard() throws Exception {
        Fixture fixture = fixture("temp-shadow", 1_000);
        PaymentService.PaymentResult payment = transactions.execute(status -> payments.record(
                fixture.tenantId(), fixture.actorId(), new PaymentService.RecordPayment(
                        fixture.customerId(), fixture.companyId(), "CNY", 100,
                        "BANK_TRANSFER", "TEST", null, OffsetDateTime.now(), null)));
        DriverManagerDataSource freshDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

        // Reproduce the pre-hardening lookup in an isolated transaction. The rollback
        // restores both the function setting and the deliberately invalid allocation.
        try (Connection vulnerableSession = freshDataSource.getConnection()) {
            vulnerableSession.setAutoCommit(false);
            try (var statement = vulnerableSession.createStatement()) {
                statement.execute("ALTER FUNCTION public.enforce_payment_balance_conservation() RESET search_path");
            }
            createTemporaryPaymentShadow(vulnerableSession, fixture.tenantId(), payment.paymentId(), 10_000);
            assertThat(insertAllocation(
                    vulnerableSession, fixture, payment.paymentId(), fixture.invoiceId(), 101)).isEqualTo(1);
            vulnerableSession.rollback();
        }

        // A brand-new session avoids a cached PL/pgSQL relation plan and proves that
        // V22 resolves the same unqualified references against public before pg_temp.
        try (Connection hardenedSession = freshDataSource.getConnection()) {
            hardenedSession.setAutoCommit(false);
            createTemporaryPaymentShadow(hardenedSession, fixture.tenantId(), payment.paymentId(), 10_000);
            assertThatThrownBy(() -> insertAllocation(
                    hardenedSession, fixture, payment.paymentId(), fixture.invoiceId(), 101))
                    .isInstanceOf(SQLException.class)
                    .satisfies(exception -> assertThat(((SQLException) exception).getSQLState())
                            .isEqualTo("23514"))
                    .hasMessageContaining("balance is not conserved");
            hardenedSession.rollback();
        }

        assertPayment(payment.paymentId(), "CONFIRMED", 0);
    }

    @Test
    void concurrentAllocationsWithinPaymentAmountCommitWithoutForeignKeyLockUpgradeDeadlock() throws Exception {
        Fixture fixture = fixture("concurrent-legal");
        UUID secondInvoiceId = additionalInvoice(fixture, "second");
        PaymentService.PaymentResult payment = transactions.execute(status -> payments.record(
                fixture.tenantId(), fixture.actorId(), new PaymentService.RecordPayment(
                        fixture.customerId(), fixture.companyId(), "CNY", 100,
                        "BANK_TRANSFER", "TEST", null, OffsetDateTime.now(), null)));

        List<ConcurrentInsertResult> results = runConcurrentAllocations(
                fixture, payment.paymentId(), fixture.invoiceId(), secondInvoiceId, 40);

        assertThat(results).allMatch(ConcurrentInsertResult::committed, results.toString());
        assertAllocationTotal(payment.paymentId(), 80);
        assertPayment(payment.paymentId(), "PARTIALLY_ALLOCATED", 2);
    }

    @Test
    void concurrentAllocationsAbovePaymentAmountRejectConstraintInsteadOfDeadlocking() throws Exception {
        Fixture fixture = fixture("concurrent-limit");
        UUID secondInvoiceId = additionalInvoice(fixture, "second");
        PaymentService.PaymentResult payment = transactions.execute(status -> payments.record(
                fixture.tenantId(), fixture.actorId(), new PaymentService.RecordPayment(
                        fixture.customerId(), fixture.companyId(), "CNY", 100,
                        "BANK_TRANSFER", "TEST", null, OffsetDateTime.now(), null)));

        List<ConcurrentInsertResult> results = runConcurrentAllocations(
                fixture, payment.paymentId(), fixture.invoiceId(), secondInvoiceId, 60);

        assertThat(results).filteredOn(ConcurrentInsertResult::committed).hasSize(1);
        assertThat(results).filteredOn(result -> !result.committed())
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.sqlState()).isEqualTo("23514");
                    assertThat(result.message()).contains("balance is not conserved");
                });
        assertThat(results).noneMatch(result -> "40P01".equals(result.sqlState()), results.toString());
        assertAllocationTotal(payment.paymentId(), 60);
        assertPayment(payment.paymentId(), "PARTIALLY_ALLOCATED", 1);
    }

    @Test
    void directAllocationsEnforceInvoiceOutstandingPartyAndCurrency() throws Exception {
        Fixture fixture = fixture("allocation-relations");
        UUID differentPartyInvoiceId = additionalInvoiceForDifferentParty(fixture, "other-party");
        UUID differentCurrencyInvoiceId = additionalInvoice(fixture, "usd", "USD");
        PaymentService.PaymentResult firstPayment = transactions.execute(status -> payments.record(
                fixture.tenantId(), fixture.actorId(), new PaymentService.RecordPayment(
                        fixture.customerId(), fixture.companyId(), "CNY", 100,
                        "BANK_TRANSFER", "TEST", null, OffsetDateTime.now(), null)));
        PaymentService.PaymentResult secondPayment = transactions.execute(status -> payments.record(
                fixture.tenantId(), fixture.actorId(), new PaymentService.RecordPayment(
                        fixture.customerId(), fixture.companyId(), "CNY", 100,
                        "BANK_TRANSFER", "TEST", null, OffsetDateTime.now(), null)));

        assertThat(insertAllocationWithFreshConnection(
                fixture, firstPayment.paymentId(), fixture.invoiceId(), 60)).isEqualTo(1);
        assertThatThrownBy(() -> insertAllocationWithFreshConnection(
                fixture, secondPayment.paymentId(), fixture.invoiceId(), 60))
                .isInstanceOf(SQLException.class)
                .satisfies(exception -> assertThat(((SQLException) exception).getSQLState()).isEqualTo("23514"))
                .hasMessageContaining("outstanding amount");
        assertThatThrownBy(() -> insertAllocationWithFreshConnection(
                fixture, secondPayment.paymentId(), differentPartyInvoiceId, 10))
                .isInstanceOf(SQLException.class)
                .satisfies(exception -> assertThat(((SQLException) exception).getSQLState()).isEqualTo("23514"))
                .hasMessageContaining("party or currency");
        assertThatThrownBy(() -> insertAllocationWithFreshConnection(
                fixture, secondPayment.paymentId(), differentCurrencyInvoiceId, 10))
                .isInstanceOf(SQLException.class)
                .satisfies(exception -> assertThat(((SQLException) exception).getSQLState()).isEqualTo("23514"))
                .hasMessageContaining("party or currency");

        assertInvoiceAllocationTotal(fixture.invoiceId(), 60);
        assertPayment(firstPayment.paymentId(), "PARTIALLY_ALLOCATED", 1);
        assertPayment(secondPayment.paymentId(), "CONFIRMED", 0);
    }

    private static List<ConcurrentInsertResult> runConcurrentAllocations(
            Fixture fixture, UUID paymentId, UUID firstInvoiceId, UUID secondInvoiceId, long amountMinor)
            throws Exception {
        CountDownLatch keyShareReady = new CountDownLatch(2);
        CountDownLatch insertStart = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ConcurrentInsertResult> first = executor.submit(() -> insertAllocationAfterKeyShare(
                    fixture, paymentId, firstInvoiceId, amountMinor, keyShareReady, insertStart));
            Future<ConcurrentInsertResult> second = executor.submit(() -> insertAllocationAfterKeyShare(
                    fixture, paymentId, secondInvoiceId, amountMinor, keyShareReady, insertStart));

            boolean bothHoldForeignKeyCompatibleLocks = keyShareReady.await(10, TimeUnit.SECONDS);
            insertStart.countDown();
            assertThat(bothHoldForeignKeyCompatibleLocks).isTrue();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally {
            insertStart.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static ConcurrentInsertResult insertAllocationAfterKeyShare(
            Fixture fixture, UUID paymentId, UUID invoiceId, long amountMinor,
            CountDownLatch keyShareReady, CountDownLatch insertStart) throws Exception {
        DriverManagerDataSource concurrentDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Connection connection = concurrentDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = '8s'");
                statement.execute("SET LOCAL statement_timeout = '12s'");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id FROM public.payments
                    WHERE tenant_id = ? AND id = ?
                    FOR KEY SHARE
                    """)) {
                statement.setObject(1, fixture.tenantId());
                statement.setObject(2, paymentId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new SQLException("payment row not found before concurrent allocation");
                    }
                }
            }

            keyShareReady.countDown();
            if (!insertStart.await(10, TimeUnit.SECONDS)) {
                connection.rollback();
                throw new SQLException("timed out waiting to start concurrent allocations", "57014");
            }

            try {
                insertAllocation(connection, fixture, paymentId, invoiceId, amountMinor);
                connection.commit();
                return new ConcurrentInsertResult(true, null, null);
            } catch (SQLException exception) {
                connection.rollback();
                return new ConcurrentInsertResult(false, exception.getSQLState(), exception.getMessage());
            }
        } finally {
            keyShareReady.countDown();
        }
    }

    private static void createTemporaryPaymentShadow(
            Connection connection, UUID tenantId, UUID paymentId, long shadowAmount) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TEMP TABLE payments (
                        tenant_id uuid NOT NULL,
                        id uuid NOT NULL,
                        amount_minor bigint NOT NULL
                    )
                    """);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO payments(tenant_id, id, amount_minor) VALUES (?, ?, ?)
                """)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, paymentId);
            statement.setLong(3, shadowAmount);
            statement.executeUpdate();
        }
    }

    private static int insertAllocation(
            Connection connection, Fixture fixture, UUID paymentId, UUID invoiceId, long amountMinor) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO public.payment_allocations(
                    id, tenant_id, payment_id, invoice_id, amount_minor, status, allocated_by
                ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, fixture.tenantId());
            statement.setObject(3, paymentId);
            statement.setObject(4, invoiceId);
            statement.setLong(5, amountMinor);
            statement.setObject(6, fixture.actorId());
            return statement.executeUpdate();
        }
    }

    private static int insertAllocationWithFreshConnection(
            Fixture fixture, UUID paymentId, UUID invoiceId, long amountMinor) throws Exception {
        DriverManagerDataSource freshDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Connection connection = freshDataSource.getConnection()) {
            return insertAllocation(connection, fixture, paymentId, invoiceId, amountMinor);
        }
    }

    private static void assertPayment(UUID paymentId, String expectedStatus, long expectedVersion) {
        PaymentRow row = jdbc.sql("SELECT status, version FROM payments WHERE id = :id")
                .param("id", paymentId)
                .query((rs, rowNumber) -> new PaymentRow(rs.getString("status"), rs.getLong("version")))
                .single();
        assertThat(row).isEqualTo(new PaymentRow(expectedStatus, expectedVersion));
    }

    private static void assertInvoicePayment(
            UUID invoiceId, String expectedStatus, long expectedVersion, boolean expectedPaidAt) {
        InvoicePaymentRow row = jdbc.sql("""
                        SELECT payment_status, version, paid_at IS NOT NULL AS has_paid_at
                        FROM invoices WHERE id = :id
                        """)
                .param("id", invoiceId)
                .query((rs, rowNumber) -> new InvoicePaymentRow(
                        rs.getString("payment_status"), rs.getLong("version"),
                        rs.getBoolean("has_paid_at")))
                .single();
        assertThat(row).isEqualTo(new InvoicePaymentRow(
                expectedStatus, expectedVersion, expectedPaidAt));
    }

    private static String findSqlState(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }

    private static void assertAllocationTotal(UUID paymentId, long expectedAmount) {
        assertThat(jdbc.sql("""
                        SELECT COALESCE(sum(amount_minor), 0)
                        FROM payment_allocations
                        WHERE payment_id = :paymentId AND status = 'ACTIVE'
                        """)
                .param("paymentId", paymentId)
                .query(Long.class).single()).isEqualTo(expectedAmount);
    }

    private static void assertInvoiceAllocationTotal(UUID invoiceId, long expectedAmount) {
        assertThat(jdbc.sql("""
                        SELECT COALESCE(sum(amount_minor), 0)
                        FROM payment_allocations
                        WHERE invoice_id = :invoiceId AND status = 'ACTIVE'
                        """)
                .param("invoiceId", invoiceId)
                .query(Long.class).single()).isEqualTo(expectedAmount);
    }

    private static UUID additionalInvoice(Fixture fixture, String label) {
        return additionalInvoice(fixture, label, "CNY");
    }

    private static UUID additionalInvoice(Fixture fixture, String label, String currencyCode) {
        UUID invoiceId = UUID.randomUUID();
        String suffix = fixture.tenantId().toString().replace("-", "") + "-" + label;
        jdbc.sql("SET session_replication_role = replica").update();
        try {
            insertInvoice(
                    fixture.tenantId(), fixture.customerId(), fixture.companyId(), invoiceId,
                    fixture.actorId(), suffix, 100, currencyCode);
        } finally {
            jdbc.sql("SET session_replication_role = DEFAULT").update();
        }
        return invoiceId;
    }

    private static UUID additionalInvoiceForDifferentParty(Fixture fixture, String label) {
        UUID customerId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        String suffix = fixture.tenantId().toString().replace("-", "") + "-" + label;
        jdbc.sql("""
                        INSERT INTO customers(id, tenant_id, customer_no, customer_name, default_currency)
                        VALUES (:id, :tenantId, :number, 'Other payment customer', 'CNY')
                        """)
                .param("id", customerId).param("tenantId", fixture.tenantId())
                .param("number", "CUST-" + suffix).update();
        jdbc.sql("""
                        INSERT INTO companies(id, tenant_id, customer_id, company_code, company_name, default_currency)
                        VALUES (:id, :tenantId, :customerId, :code, 'Other payment company', 'CNY')
                        """)
                .param("id", companyId).param("tenantId", fixture.tenantId())
                .param("customerId", customerId).param("code", "COMP-" + suffix).update();
        jdbc.sql("SET session_replication_role = replica").update();
        try {
            insertInvoice(fixture.tenantId(), customerId, companyId, invoiceId,
                    fixture.actorId(), suffix, 100, "CNY");
        } finally {
            jdbc.sql("SET session_replication_role = DEFAULT").update();
        }
        return invoiceId;
    }

    private static Fixture fixture(String label) {
        return fixture(label, 100);
    }

    private static Fixture fixture(String label, long invoiceTotalMinor) {
        UUID tenantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        String suffix = tenantId.toString().replace("-", "");

        jdbc.sql("INSERT INTO tenants(id, tenant_code, tenant_name) VALUES (:id, :code, :name)")
                .param("id", tenantId).param("code", label + "-" + suffix).param("name", label).update();
        jdbc.sql("""
                        INSERT INTO users(id, tenant_id, username, email, display_name)
                        VALUES (:id, :tenantId, :username, :email, 'Payment operator')
                        """)
                .param("id", actorId).param("tenantId", tenantId).param("username", label + "-" + suffix)
                .param("email", label + "-" + suffix + "@example.invalid").update();
        jdbc.sql("""
                        INSERT INTO customers(id, tenant_id, customer_no, customer_name, default_currency)
                        VALUES (:id, :tenantId, :number, 'Payment customer', 'CNY')
                        """)
                .param("id", customerId).param("tenantId", tenantId).param("number", "CUST-" + suffix).update();
        jdbc.sql("""
                        INSERT INTO companies(id, tenant_id, customer_id, company_code, company_name, default_currency)
                        VALUES (:id, :tenantId, :customerId, :code, 'Payment company', 'CNY')
                        """)
                .param("id", companyId).param("tenantId", tenantId).param("customerId", customerId)
                .param("code", "COMP-" + suffix).update();

        jdbc.sql("SET session_replication_role = replica").update();
        try {
            insertInvoice(tenantId, customerId, companyId, invoiceId, actorId, suffix,
                    invoiceTotalMinor, "CNY");
        } finally {
            jdbc.sql("SET session_replication_role = DEFAULT").update();
        }
        return new Fixture(tenantId, actorId, customerId, companyId, invoiceId);
    }

    private static void setInvoiceDueDateWithReplica(UUID invoiceId, int daysFromToday) {
        jdbc.sql("SET session_replication_role = replica").update();
        try {
            jdbc.sql("UPDATE invoices SET due_date = current_date + :days WHERE id = :id")
                    .param("days", daysFromToday).param("id", invoiceId).update();
        } finally {
            jdbc.sql("SET session_replication_role = DEFAULT").update();
        }
    }

    private static void insertInvoice(UUID tenantId, UUID customerId, UUID companyId, UUID invoiceId,
                                      UUID actorId, String suffix, long totalMinor, String currencyCode) {
        jdbc.sql("""
                        INSERT INTO invoices(
                            id, tenant_id, invoice_number, source_preview_id, invoice_profile_id,
                            customer_id, company_id, template_id, template_version_id, approval_instance_id,
                            period_start, period_end, issue_date, due_date, timezone, language, currency_code,
                            subtotal_minor, discount_minor, tax_minor, adjustment_minor, total_minor,
                            party_snapshot_json, profile_snapshot_json, render_model_json, data_snapshot_hash,
                            document_status, send_status, payment_status, finalized_by, confirmed_at
                        ) VALUES (
                            :id, :tenantId, :number, :sourcePreviewId, :profileId,
                            :customerId, :companyId, :templateId, :templateVersionId, :approvalInstanceId,
                            now() - interval '1 month', now(), current_date - 30, current_date + 7,
                            'Asia/Shanghai', 'zh-CN', :currencyCode,
                            :totalMinor, 0, 0, 0, :totalMinor,
                            '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, repeat('0', 64),
                            'CONFIRMED', 'NOT_QUEUED', 'UNPAID', :actorId, now()
                        )
                        """)
                .param("id", invoiceId).param("tenantId", tenantId).param("number", "INV-" + suffix)
                .param("sourcePreviewId", UUID.randomUUID()).param("profileId", UUID.randomUUID())
                .param("customerId", customerId).param("companyId", companyId)
                .param("templateId", UUID.randomUUID()).param("templateVersionId", UUID.randomUUID())
                .param("approvalInstanceId", UUID.randomUUID()).param("actorId", actorId)
                .param("currencyCode", currencyCode).param("totalMinor", totalMinor).update();
    }

    private record Fixture(UUID tenantId, UUID actorId, UUID customerId, UUID companyId, UUID invoiceId) {
    }

    private record PaymentRow(String status, long version) {
    }

    private record InvoicePaymentRow(String status, long version, boolean hasPaidAt) {
    }

    private record ConcurrentInsertResult(boolean committed, String sqlState, String message) {
    }
}
