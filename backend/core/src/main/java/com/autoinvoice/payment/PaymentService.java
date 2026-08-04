package com.autoinvoice.payment;

import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.numbering.NumberSequenceService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentService {
    private final JdbcClient jdbc;
    private final NumberSequenceService numberSequenceService;

    public PaymentService(JdbcClient jdbc, NumberSequenceService numberSequenceService) {
        this.jdbc = jdbc;
        this.numberSequenceService = numberSequenceService;
    }

    @Transactional
    public PaymentResult record(UUID tenantId, UUID actorId, RecordPayment command) {
        validatePartyAndCurrency(tenantId, command.customerId(), command.companyId(), command.currencyCode());
        String period = command.paidAt().format(DateTimeFormatter.ofPattern("yyyyMM"));
        long sequence = numberSequenceService.next(tenantId, "payment", period, 6);
        String paymentNumber = "PAY-%s-%06d".formatted(period, sequence);
        UUID id = UuidV7.generate();
        jdbc.sql("""
                        INSERT INTO payments(
                            id, tenant_id, payment_number, customer_id, company_id, currency_code,
                            amount_minor, payment_method, source_system, external_reference,
                            paid_at, status, notes, created_by
                        ) VALUES (
                            :id, :tenantId, :number, :customerId, :companyId, :currency,
                            :amount, :method, :sourceSystem, :externalReference,
                            :paidAt, 'CONFIRMED', :notes, :actorId
                        )
                        """)
                .param("id", id).param("tenantId", tenantId).param("number", paymentNumber)
                .param("customerId", command.customerId()).param("companyId", command.companyId())
                .param("currency", command.currencyCode()).param("amount", command.amountMinor())
                .param("method", command.paymentMethod()).param("sourceSystem", command.sourceSystem())
                .param("externalReference", command.externalReference()).param("paidAt", command.paidAt())
                .param("notes", command.notes()).param("actorId", actorId).update();
        return new PaymentResult(id, paymentNumber, command.amountMinor(), 0, "CONFIRMED");
    }

    @Transactional
    public AllocationResult allocate(UUID tenantId, UUID actorId, UUID paymentId, AllocatePayment command) {
        PaymentState payment = jdbc.sql("""
                        SELECT id, customer_id, company_id, currency_code, amount_minor, status, version
                        FROM payments WHERE tenant_id = :tenantId AND id = :paymentId FOR UPDATE
                        """)
                .param("tenantId", tenantId).param("paymentId", paymentId)
                .query((rs, rowNum) -> new PaymentState(rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class), rs.getObject("company_id", UUID.class),
                        rs.getString("currency_code"), rs.getLong("amount_minor"), rs.getString("status"),
                        rs.getLong("version")))
                .optional().orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Payment was not found", 404,
                        Map.of("payment_id", paymentId)));
        InvoiceState invoice = jdbc.sql("""
                        SELECT id, customer_id, company_id, currency_code, total_minor, document_status, version
                        FROM invoices WHERE tenant_id = :tenantId AND id = :invoiceId FOR UPDATE
                        """)
                .param("tenantId", tenantId).param("invoiceId", command.invoiceId())
                .query((rs, rowNum) -> new InvoiceState(rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class), rs.getObject("company_id", UUID.class),
                        rs.getString("currency_code"), rs.getLong("total_minor"), rs.getString("document_status"),
                        rs.getLong("version")))
                .optional().orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Invoice was not found", 404,
                        Map.of("invoice_id", command.invoiceId())));

        if (!payment.customerId().equals(invoice.customerId())
                || (payment.companyId() != null && !payment.companyId().equals(invoice.companyId()))
                || !payment.currencyCode().equals(invoice.currencyCode())) {
            throw new DomainException("PAYMENT_PARTY_OR_CURRENCY_MISMATCH",
                    "Payment and invoice customer, company and currency must match", 409, Map.of());
        }
        if (payment.version() != command.expectedPaymentVersion()) {
            throw new DomainException("VERSION_CONFLICT", "Payment was modified by another request", 409,
                    Map.of("expected_version", command.expectedPaymentVersion(), "current_version", payment.version()));
        }
        if (!("CONFIRMED".equals(invoice.documentStatus()) || "SENT".equals(invoice.documentStatus()))) {
            throw new DomainException("INVOICE_NOT_PAYABLE", "Invoice is not in a payable state", 409,
                    Map.of("invoice_status", invoice.documentStatus()));
        }
        long allocatedFromPayment = allocatedForPayment(tenantId, paymentId);
        long refundedFromPayment = refundedForPayment(tenantId, paymentId);
        PaymentBalance paymentBalance = paymentBalance(payment.amountMinor(), allocatedFromPayment, refundedFromPayment);
        if (!paymentBalance.allocatable()) {
            throw new DomainException("PAYMENT_NOT_ALLOCATABLE", "Payment is not available for allocation", 409,
                    Map.of("payment_status", paymentBalance.status()));
        }
        long allocatedToInvoice = allocatedToInvoice(tenantId, invoice.id());
        long paymentAvailable = paymentBalance.availableMinor();
        long invoiceOutstanding = invoice.totalMinor() - allocatedToInvoice;
        if (command.amountMinor() <= 0 || command.amountMinor() > paymentAvailable || command.amountMinor() > invoiceOutstanding) {
            throw new DomainException("PAYMENT_ALLOCATION_EXCEEDS_AVAILABLE",
                    "Allocation exceeds the payment balance or invoice outstanding amount", 409,
                    Map.of("payment_available_minor", Long.toString(paymentAvailable),
                            "invoice_outstanding_minor", Long.toString(invoiceOutstanding)));
        }

        UUID allocationId = UuidV7.generate();
        jdbc.sql("""
                        INSERT INTO payment_allocations(
                            id, tenant_id, payment_id, invoice_id, amount_minor, status, allocated_by
                        ) VALUES (:id, :tenantId, :paymentId, :invoiceId, :amount, 'ACTIVE', :actorId)
                        """)
                .param("id", allocationId).param("tenantId", tenantId).param("paymentId", paymentId)
                .param("invoiceId", invoice.id()).param("amount", command.amountMinor()).param("actorId", actorId)
                .update();

        long paymentAllocatedAfter = allocatedFromPayment + command.amountMinor();
        long invoiceAllocatedAfter = allocatedToInvoice + command.amountMinor();
        PaymentBalance paymentBalanceAfter = paymentBalance(
                payment.amountMinor(), paymentAllocatedAfter, refundedFromPayment);
        String paymentStatus = paymentBalanceAfter.status();
        String invoiceStatus = invoicePaymentStatus(tenantId, invoice.id());
        return new AllocationResult(allocationId, paymentId, invoice.id(), command.amountMinor(),
                paymentStatus, invoiceStatus, paymentBalanceAfter.availableMinor(),
                invoice.totalMinor() - invoiceAllocatedAfter);
    }

    @Transactional
    public AllocationResult reverseAllocation(UUID tenantId, UUID actorId, UUID paymentId,
                                              UUID allocationId, long expectedPaymentVersion, String reason) {
        AllocationState allocation = jdbc.sql("""
                        SELECT allocation.id, allocation.payment_id, allocation.invoice_id, allocation.amount_minor,
                               allocation.status, payment.amount_minor AS payment_amount,
                               payment.version AS payment_version, invoice.total_minor AS invoice_total
                        FROM payment_allocations allocation
                        JOIN payments payment ON payment.tenant_id = allocation.tenant_id
                             AND payment.id = allocation.payment_id
                        JOIN invoices invoice ON invoice.tenant_id = allocation.tenant_id
                             AND invoice.id = allocation.invoice_id
                        WHERE allocation.tenant_id = :tenantId AND allocation.payment_id = :paymentId
                          AND allocation.id = :allocationId
                        FOR UPDATE OF allocation, payment, invoice
                        """)
                .param("tenantId", tenantId).param("paymentId", paymentId).param("allocationId", allocationId)
                .query((rs, row) -> new AllocationState(rs.getObject("id", UUID.class),
                        rs.getObject("payment_id", UUID.class), rs.getObject("invoice_id", UUID.class),
                        rs.getLong("amount_minor"), rs.getString("status"), rs.getLong("payment_amount"),
                        rs.getLong("payment_version"), rs.getLong("invoice_total"))).optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Payment allocation was not found", 404,
                        Map.of("allocation_id", allocationId)));
        if (!"ACTIVE".equals(allocation.status())) {
            throw new DomainException("PAYMENT_ALLOCATION_NOT_ACTIVE", "Only an active allocation can be reversed", 409,
                    Map.of("allocation_id", allocationId));
        }
        if (allocation.paymentVersion() != expectedPaymentVersion) {
            throw new DomainException("VERSION_CONFLICT", "Payment was modified by another request", 409,
                    Map.of("expected_version", expectedPaymentVersion, "current_version", allocation.paymentVersion()));
        }
        jdbc.sql("""
                        UPDATE payment_allocations SET status = 'REVERSED', reversed_by = :actorId,
                            reversed_at = now(), reversal_reason = :reason
                        WHERE tenant_id = :tenantId AND id = :allocationId AND status = 'ACTIVE'
                        """)
                .param("actorId", actorId).param("reason", reason).param("tenantId", tenantId)
                .param("allocationId", allocationId).update();
        long paymentAllocated = allocatedForPayment(tenantId, paymentId);
        long paymentRefunded = refundedForPayment(tenantId, paymentId);
        PaymentBalance paymentBalance = paymentBalance(
                allocation.paymentAmount(), paymentAllocated, paymentRefunded);
        long invoiceAllocated = allocatedToInvoice(tenantId, allocation.invoiceId());
        String paymentStatus = paymentBalance.status();
        String invoiceStatus = invoicePaymentStatus(tenantId, allocation.invoiceId());
        return new AllocationResult(allocation.id(), paymentId, allocation.invoiceId(), allocation.amountMinor(),
                paymentStatus, invoiceStatus, paymentBalance.availableMinor(),
                allocation.invoiceTotal() - invoiceAllocated);
    }

    @Transactional
    public RefundResult refund(UUID tenantId, UUID actorId, UUID paymentId, long expectedVersion,
                               long amountMinor, OffsetDateTime refundedAt, String externalReference,
                               String reason) {
        PaymentState payment = jdbc.sql("""
                        SELECT id, customer_id, company_id, currency_code, amount_minor, status, version
                        FROM payments WHERE tenant_id = :tenantId AND id = :paymentId FOR UPDATE
                        """)
                .param("tenantId", tenantId).param("paymentId", paymentId)
                .query((rs, row) -> new PaymentState(rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class), rs.getObject("company_id", UUID.class),
                        rs.getString("currency_code"), rs.getLong("amount_minor"), rs.getString("status"),
                        rs.getLong("version"))).optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Payment was not found", 404,
                        Map.of("payment_id", paymentId)));
        if (payment.version() != expectedVersion) {
            throw new DomainException("VERSION_CONFLICT", "Payment was modified by another request", 409,
                    Map.of("expected_version", expectedVersion, "current_version", payment.version()));
        }
        long allocated = allocatedForPayment(tenantId, paymentId);
        long refunded = refundedForPayment(tenantId, paymentId);
        PaymentBalance paymentBalance = paymentBalance(payment.amountMinor(), allocated, refunded);
        long refundable = paymentBalance.availableMinor();
        if (amountMinor <= 0 || amountMinor > refundable) {
            throw new DomainException("PAYMENT_REFUND_EXCEEDS_AVAILABLE",
                    "Refund exceeds the unallocated, unrefunded payment balance", 409,
                    Map.of("refundable_minor", Long.toString(refundable),
                            "allocated_minor", Long.toString(allocated)));
        }
        String period = refundedAt.format(DateTimeFormatter.ofPattern("yyyyMM"));
        long sequence = numberSequenceService.next(tenantId, "refund", period, 6);
        String refundNumber = "REF-%s-%06d".formatted(period, sequence);
        UUID refundId = UuidV7.generate();
        jdbc.sql("""
                        INSERT INTO payment_refunds(
                            id, tenant_id, payment_id, refund_number, amount_minor, reason,
                            external_reference, status, refunded_at, created_by
                        ) VALUES (
                            :id, :tenantId, :paymentId, :number, :amount, :reason,
                            :externalReference, 'CONFIRMED', :refundedAt, :actorId
                        )
                        """)
                .param("id", refundId).param("tenantId", tenantId).param("paymentId", paymentId)
                .param("number", refundNumber).param("amount", amountMinor).param("reason", reason)
                .param("externalReference", externalReference).param("refundedAt", refundedAt)
                .param("actorId", actorId).update();
        long refundedAfter = refunded + amountMinor;
        PaymentBalance paymentBalanceAfter = paymentBalance(payment.amountMinor(), allocated, refundedAfter);
        String status = paymentBalanceAfter.status();
        return new RefundResult(refundId, refundNumber, paymentId, amountMinor, refundedAfter,
                paymentBalanceAfter.availableMinor(), status);
    }

    private long allocatedForPayment(UUID tenantId, UUID paymentId) {
        return jdbc.sql("""
                        SELECT COALESCE(sum(amount_minor), 0) FROM payment_allocations
                        WHERE tenant_id = :tenantId AND payment_id = :paymentId AND status = 'ACTIVE'
                        """)
                .param("tenantId", tenantId).param("paymentId", paymentId).query(Long.class).single();
    }

    private long allocatedToInvoice(UUID tenantId, UUID invoiceId) {
        return jdbc.sql("""
                        SELECT COALESCE(sum(amount_minor), 0) FROM payment_allocations
                        WHERE tenant_id = :tenantId AND invoice_id = :invoiceId AND status = 'ACTIVE'
                        """)
                .param("tenantId", tenantId).param("invoiceId", invoiceId).query(Long.class).single();
    }

    private String invoicePaymentStatus(UUID tenantId, UUID invoiceId) {
        return jdbc.sql("""
                        SELECT payment_status
                        FROM invoices
                        WHERE tenant_id = :tenantId AND id = :invoiceId
                        """)
                .param("tenantId", tenantId)
                .param("invoiceId", invoiceId)
                .query(String.class)
                .single();
    }

    private long refundedForPayment(UUID tenantId, UUID paymentId) {
        return jdbc.sql("""
                        SELECT COALESCE(sum(amount_minor), 0) FROM payment_refunds
                        WHERE tenant_id = :tenantId AND payment_id = :paymentId AND status = 'CONFIRMED'
                        """)
                .param("tenantId", tenantId).param("paymentId", paymentId).query(Long.class).single();
    }

    static PaymentBalance paymentBalance(long amountMinor, long allocatedMinor, long refundedMinor) {
        if (amountMinor <= 0 || allocatedMinor < 0 || refundedMinor < 0
                || allocatedMinor > amountMinor || refundedMinor > amountMinor - allocatedMinor) {
            throw new DomainException("PAYMENT_BALANCE_INVARIANT_BROKEN",
                    "Payment allocations and confirmed refunds exceed the payment amount", 409,
                    Map.of("amount_minor", Long.toString(amountMinor),
                            "allocated_minor", Long.toString(allocatedMinor),
                            "refunded_minor", Long.toString(refundedMinor)));
        }
        return new PaymentBalance(amountMinor, allocatedMinor, refundedMinor);
    }

    private void validatePartyAndCurrency(UUID tenantId, UUID customerId, UUID companyId, String currencyCode) {
        boolean valid = jdbc.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM customers customer
                            LEFT JOIN companies company ON company.tenant_id = customer.tenant_id
                                 AND company.customer_id = customer.id AND company.id = :companyId
                            JOIN currencies currency ON currency.code = :currency AND currency.enabled
                            WHERE customer.tenant_id = :tenantId AND customer.id = :customerId
                              AND (:companyId IS NULL OR company.id IS NOT NULL)
                        )
                        """)
                .param("tenantId", tenantId).param("customerId", customerId).param("companyId", companyId)
                .param("currency", currencyCode).query(Boolean.class).single();
        if (!valid) {
            throw new DomainException("PAYMENT_PARTY_OR_CURRENCY_INVALID",
                    "Customer, company and currency must belong to the current tenant", 422, Map.of());
        }
    }

    public record RecordPayment(UUID customerId, UUID companyId, String currencyCode, long amountMinor,
                                String paymentMethod, String sourceSystem, String externalReference,
                                OffsetDateTime paidAt, String notes) {
    }

    public record AllocatePayment(UUID invoiceId, long amountMinor, long expectedPaymentVersion) {
    }

    public record PaymentResult(UUID paymentId, String paymentNumber, long amountMinor,
                                long allocatedMinor, String status) {
    }

    public record AllocationResult(UUID allocationId, UUID paymentId, UUID invoiceId, long amountMinor,
                                   String paymentStatus, String invoicePaymentStatus,
                                   long paymentAvailableMinor, long invoiceOutstandingMinor) {
    }

    public record RefundResult(UUID refundId, String refundNumber, UUID paymentId, long amountMinor,
                               long refundedMinor, long refundableMinor, String paymentStatus) {
    }

    private record PaymentState(UUID id, UUID customerId, UUID companyId, String currencyCode,
                                long amountMinor, String status, long version) {
    }

    private record InvoiceState(UUID id, UUID customerId, UUID companyId, String currencyCode,
                                long totalMinor, String documentStatus, long version) {
    }

    private record AllocationState(UUID id, UUID paymentId, UUID invoiceId, long amountMinor, String status,
                                   long paymentAmount, long paymentVersion, long invoiceTotal) {
    }

    record PaymentBalance(long amountMinor, long allocatedMinor, long refundedMinor) {
        long availableMinor() {
            return amountMinor - allocatedMinor - refundedMinor;
        }

        boolean allocatable() {
            return availableMinor() > 0;
        }

        String status() {
            if (refundedMinor == amountMinor) {
                return "REFUNDED";
            }
            if (refundedMinor > 0) {
                return "PARTIALLY_REFUNDED";
            }
            if (allocatedMinor == amountMinor) {
                return "ALLOCATED";
            }
            if (allocatedMinor > 0) {
                return "PARTIALLY_ALLOCATED";
            }
            return "CONFIRMED";
        }
    }
}
