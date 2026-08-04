package com.autoinvoice.api.report;

import com.autoinvoice.api.security.AuthenticatedUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports")
public class ReceivablesReportController {
    private final JdbcClient jdbc;

    public ReceivablesReportController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/receivables")
    @PreAuthorize("hasAnyAuthority('payment.record','audit.read','system.admin')")
    public ReceivablesReport receivables(Authentication authentication,
                                         @RequestParam(name = "as_of", required = false) LocalDate asOf) {
        UUID tenantId = ((AuthenticatedUser) authentication.getPrincipal()).tenantId();
        LocalDate effectiveDate = asOf == null ? LocalDate.now() : asOf;
        List<CurrencySummary> currencies = jdbc.sql("""
                        WITH invoice_balance AS (
                            SELECT invoice.id, invoice.currency_code, invoice.total_minor, invoice.due_date,
                                   GREATEST(invoice.total_minor - COALESCE(sum(allocation.amount_minor)
                                       FILTER (WHERE allocation.status = 'ACTIVE'), 0), 0) AS outstanding_minor
                            FROM invoices invoice
                            LEFT JOIN payment_allocations allocation
                              ON allocation.tenant_id = invoice.tenant_id AND allocation.invoice_id = invoice.id
                            WHERE invoice.tenant_id = :tenantId
                              AND invoice.document_status IN ('CONFIRMED','SENT','REPLACED')
                            GROUP BY invoice.id
                        )
                        SELECT currency_code,
                               sum(total_minor) AS invoiced_minor,
                               sum(total_minor - outstanding_minor) AS allocated_minor,
                               sum(outstanding_minor) AS outstanding_minor,
                               sum(outstanding_minor) FILTER (WHERE due_date < :asOf) AS overdue_minor,
                               count(*) FILTER (WHERE outstanding_minor > 0) AS open_invoice_count
                        FROM invoice_balance
                        GROUP BY currency_code ORDER BY currency_code
                        """)
                .param("tenantId", tenantId).param("asOf", effectiveDate)
                .query((rs, row) -> new CurrencySummary(rs.getString("currency_code"),
                        rs.getLong("invoiced_minor"), rs.getLong("allocated_minor"),
                        rs.getLong("outstanding_minor"), rs.getLong("overdue_minor"),
                        rs.getLong("open_invoice_count"))).list();
        List<AgingBucket> aging = jdbc.sql("""
                        WITH invoice_balance AS (
                            SELECT invoice.id, invoice.currency_code, invoice.due_date,
                                   GREATEST(invoice.total_minor - COALESCE(sum(allocation.amount_minor)
                                       FILTER (WHERE allocation.status = 'ACTIVE'), 0), 0) AS outstanding_minor
                            FROM invoices invoice
                            LEFT JOIN payment_allocations allocation
                              ON allocation.tenant_id = invoice.tenant_id AND allocation.invoice_id = invoice.id
                            WHERE invoice.tenant_id = :tenantId
                              AND invoice.document_status IN ('CONFIRMED','SENT','REPLACED')
                            GROUP BY invoice.id
                        )
                        SELECT currency_code,
                               CASE
                                   WHEN due_date >= :asOf THEN 'CURRENT'
                                   WHEN :asOf - due_date BETWEEN 1 AND 30 THEN '1_30'
                                   WHEN :asOf - due_date BETWEEN 31 AND 60 THEN '31_60'
                                   WHEN :asOf - due_date BETWEEN 61 AND 90 THEN '61_90'
                                   ELSE 'OVER_90'
                               END AS bucket,
                               sum(outstanding_minor) AS outstanding_minor,
                               count(*) AS invoice_count
                        FROM invoice_balance
                        WHERE outstanding_minor > 0
                        GROUP BY currency_code, bucket
                        ORDER BY currency_code,
                            CASE bucket WHEN 'CURRENT' THEN 0 WHEN '1_30' THEN 1 WHEN '31_60' THEN 2
                                        WHEN '61_90' THEN 3 ELSE 4 END
                        """)
                .param("tenantId", tenantId).param("asOf", effectiveDate)
                .query((rs, row) -> new AgingBucket(rs.getString("currency_code"), rs.getString("bucket"),
                        rs.getLong("outstanding_minor"), rs.getLong("invoice_count"))).list();
        List<OutstandingInvoice> largest = jdbc.sql("""
                        SELECT invoice.id, invoice.invoice_number, invoice.customer_id, invoice.company_id,
                               invoice.currency_code, invoice.issue_date, invoice.due_date,
                               GREATEST(invoice.total_minor - COALESCE(sum(allocation.amount_minor)
                                   FILTER (WHERE allocation.status = 'ACTIVE'), 0), 0) AS outstanding_minor
                        FROM invoices invoice
                        LEFT JOIN payment_allocations allocation
                          ON allocation.tenant_id = invoice.tenant_id AND allocation.invoice_id = invoice.id
                        WHERE invoice.tenant_id = :tenantId
                          AND invoice.document_status IN ('CONFIRMED','SENT','REPLACED')
                        GROUP BY invoice.id
                        HAVING GREATEST(invoice.total_minor - COALESCE(sum(allocation.amount_minor)
                            FILTER (WHERE allocation.status = 'ACTIVE'), 0), 0) > 0
                        ORDER BY outstanding_minor DESC, invoice.due_date, invoice.invoice_number
                        LIMIT 50
                        """)
                .param("tenantId", tenantId).query((rs, row) -> new OutstandingInvoice(
                        rs.getObject("id", UUID.class), rs.getString("invoice_number"),
                        rs.getObject("customer_id", UUID.class), rs.getObject("company_id", UUID.class),
                        rs.getString("currency_code"), rs.getObject("issue_date", LocalDate.class),
                        rs.getObject("due_date", LocalDate.class), rs.getLong("outstanding_minor"))).list();
        return new ReceivablesReport(effectiveDate, currencies, aging, largest);
    }

    public record ReceivablesReport(LocalDate asOf, List<CurrencySummary> currencies,
                                    List<AgingBucket> aging, List<OutstandingInvoice> largestOutstanding) {
    }

    public record CurrencySummary(String currencyCode, long invoicedMinor, long allocatedMinor,
                                  long outstandingMinor, long overdueMinor, long openInvoiceCount) {
    }

    public record AgingBucket(String currencyCode, String bucket,
                              long outstandingMinor, long invoiceCount) {
    }

    public record OutstandingInvoice(UUID invoiceId, String invoiceNumber, UUID customerId, UUID companyId,
                                     String currencyCode, LocalDate issueDate, LocalDate dueDate,
                                     long outstandingMinor) {
    }
}
