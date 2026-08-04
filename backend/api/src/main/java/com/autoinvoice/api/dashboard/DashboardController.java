package com.autoinvoice.api.dashboard;

import com.autoinvoice.api.security.AuthenticatedUser;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
    private final JdbcClient jdbc;

    public DashboardController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/summary")
    public DashboardSummary summary(Authentication authentication) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        UUID tenantId = user.tenantId();
        DashboardAccess access = accessFor(user.permissions());

        Long customers = access.customerMetrics() ? count("customers", tenantId, null) : null;
        Long activeServices = access.customerMetrics()
                ? count("services", tenantId, "status = 'ACTIVE'") : null;
        Long previewsAwaitingReview = access.previewMetrics()
                ? jdbc.sql("""
                                SELECT count(*) FROM invoice_previews
                                WHERE tenant_id = :tenantId
                                  AND status IN ('BUSINESS_REVIEW', 'FINANCE_REVIEW')
                                """).param("tenantId", tenantId).query(Long.class).single()
                : null;
        Long finalizing = access.invoiceMetrics()
                ? count("invoices", tenantId, "document_status = 'FINALIZING'") : null;
        Long deadJobs = access.jobMetrics()
                ? count("background_jobs", tenantId, "status = 'DEAD'") : null;
        List<ReceivableBalance> receivables = access.receivableMetrics() ? receivables(tenantId) : null;
        return new DashboardSummary(customers, activeServices, previewsAwaitingReview, finalizing, deadJobs,
                receivables);
    }

    static DashboardAccess accessFor(Set<String> permissions) {
        return new DashboardAccess(
                permissions.contains("customer.read"),
                hasAny(permissions, "preview.generate", "preview.adjust", "preview.approve.business",
                        "preview.approve.finance", "invoice.finalize"),
                hasAny(permissions, "invoice.finalize", "invoice.send", "invoice.void", "payment.record",
                        "audit.read"),
                hasAny(permissions, "audit.read", "system.admin"),
                hasAny(permissions, "payment.record", "audit.read", "system.admin")
        );
    }

    private static boolean hasAny(Set<String> permissions, String... candidates) {
        for (String candidate : candidates) {
            if (permissions.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private long count(String table, UUID tenantId, String condition) {
        String safeTable = switch (table) {
            case "customers", "services", "invoices", "background_jobs" -> table;
            default -> throw new IllegalArgumentException("Unsupported dashboard table");
        };
        String sql = "SELECT count(*) FROM " + safeTable + " WHERE tenant_id = :tenantId"
                + (condition == null ? "" : " AND " + condition);
        return jdbc.sql(sql).param("tenantId", tenantId).query(Long.class).single();
    }

    private List<ReceivableBalance> receivables(UUID tenantId) {
        return jdbc.sql("""
                        WITH invoice_balance AS (
                            SELECT invoice.id, invoice.currency_code,
                                   GREATEST(invoice.total_minor - COALESCE(sum(allocation.amount_minor)
                                       FILTER (WHERE allocation.status = 'ACTIVE'), 0), 0) AS outstanding_minor
                            FROM invoices invoice
                            LEFT JOIN payment_allocations allocation
                              ON allocation.tenant_id = invoice.tenant_id
                             AND allocation.invoice_id = invoice.id
                            WHERE invoice.tenant_id = :tenantId
                              AND invoice.document_status IN ('CONFIRMED', 'SENT', 'REPLACED')
                            GROUP BY invoice.id
                        )
                        SELECT balance.currency_code, currency.symbol, currency.minor_unit,
                               sum(balance.outstanding_minor) AS outstanding_minor
                        FROM invoice_balance balance
                        JOIN currencies currency ON currency.code = balance.currency_code
                        WHERE balance.outstanding_minor > 0
                        GROUP BY balance.currency_code, currency.symbol, currency.minor_unit
                        ORDER BY balance.currency_code
                        """)
                .param("tenantId", tenantId)
                .query(this::mapReceivable)
                .list();
    }

    private ReceivableBalance mapReceivable(ResultSet rs, int row) throws SQLException {
        return new ReceivableBalance(rs.getString("currency_code").trim(), rs.getString("symbol"),
                rs.getInt("minor_unit"), rs.getLong("outstanding_minor"));
    }

    record DashboardAccess(boolean customerMetrics, boolean previewMetrics, boolean invoiceMetrics,
                           boolean jobMetrics, boolean receivableMetrics) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DashboardSummary(Long customers, Long activeServices, Long previewsAwaitingReview,
                                   Long invoicesFinalizing, Long deadJobs, List<ReceivableBalance> receivables) {
    }

    public record ReceivableBalance(String currencyCode, String currencySymbol,
                                    int minorUnit, long outstandingMinor) {
    }
}
