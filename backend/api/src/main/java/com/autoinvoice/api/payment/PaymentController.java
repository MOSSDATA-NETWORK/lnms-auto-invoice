package com.autoinvoice.api.payment;

import com.autoinvoice.api.idempotency.IdempotencyExecutor;
import com.autoinvoice.api.http.VersionEtag;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.payment.PaymentService;
import com.autoinvoice.platform.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private final JdbcClient jdbc;
    private final PaymentService paymentService;
    private final IdempotencyExecutor idempotencyExecutor;
    private final AuditService auditService;

    public PaymentController(JdbcClient jdbc, PaymentService paymentService,
                             IdempotencyExecutor idempotencyExecutor, AuditService auditService) {
        this.jdbc = jdbc;
        this.paymentService = paymentService;
        this.idempotencyExecutor = idempotencyExecutor;
        this.auditService = auditService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('payment.record')")
    public List<PaymentSummary> list(Authentication authentication, @RequestParam(defaultValue = "50") int limit) {
        UUID tenantId = principal(authentication).tenantId();
        return jdbc.sql("""
                        SELECT payment.id, payment.payment_number, payment.customer_id, payment.company_id,
                               payment.currency_code, payment.amount_minor, payment.payment_method, payment.paid_at,
                               payment.status, payment.external_reference, payment.version,
                               COALESCE(sum(allocation.amount_minor) FILTER (WHERE allocation.status = 'ACTIVE'), 0) AS allocated_minor,
                               COALESCE((SELECT sum(refund.amount_minor) FROM payment_refunds refund
                                         WHERE refund.tenant_id = payment.tenant_id AND refund.payment_id = payment.id
                                           AND refund.status = 'CONFIRMED'), 0) AS refunded_minor
                        FROM payments payment
                        LEFT JOIN payment_allocations allocation
                          ON allocation.tenant_id = payment.tenant_id AND allocation.payment_id = payment.id
                        WHERE payment.tenant_id = :tenantId
                        GROUP BY payment.id
                        ORDER BY payment.paid_at DESC
                        LIMIT :limit
                        """)
                .param("tenantId", tenantId).param("limit", Math.max(1, Math.min(limit, 200)))
                .query(this::map).list();
    }

    @GetMapping("/{paymentId}")
    @PreAuthorize("hasAuthority('payment.record')")
    public ResponseEntity<PaymentDetail> get(Authentication authentication, @PathVariable UUID paymentId) {
        UUID tenantId = principal(authentication).tenantId();
        PaymentSummary summary = jdbc.sql("""
                        SELECT payment.id, payment.payment_number, payment.customer_id, payment.company_id,
                               payment.currency_code, payment.amount_minor, payment.payment_method, payment.paid_at,
                               payment.status, payment.external_reference, payment.version,
                               COALESCE((SELECT sum(amount_minor) FROM payment_allocations allocation
                                         WHERE allocation.tenant_id = payment.tenant_id AND allocation.payment_id = payment.id
                                           AND allocation.status = 'ACTIVE'), 0) AS allocated_minor,
                               COALESCE((SELECT sum(amount_minor) FROM payment_refunds refund
                                         WHERE refund.tenant_id = payment.tenant_id AND refund.payment_id = payment.id
                                           AND refund.status = 'CONFIRMED'), 0) AS refunded_minor
                        FROM payments payment WHERE payment.tenant_id = :tenantId AND payment.id = :paymentId
                        """)
                .param("tenantId", tenantId).param("paymentId", paymentId).query(this::map).optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Payment was not found", 404,
                        Map.of("payment_id", paymentId)));
        List<AllocationResponse> allocations = jdbc.sql("""
                        SELECT * FROM payment_allocations WHERE tenant_id = :tenantId AND payment_id = :paymentId
                        ORDER BY allocated_at, id
                        """)
                .param("tenantId", tenantId).param("paymentId", paymentId).query((rs, row) -> new AllocationResponse(
                        rs.getObject("id", UUID.class), rs.getObject("invoice_id", UUID.class),
                        rs.getLong("amount_minor"), rs.getString("status"),
                        rs.getObject("allocated_at", OffsetDateTime.class), rs.getObject("reversed_at", OffsetDateTime.class),
                        rs.getString("reversal_reason"))).list();
        List<RefundResponse> refunds = jdbc.sql("""
                        SELECT * FROM payment_refunds WHERE tenant_id = :tenantId AND payment_id = :paymentId
                        ORDER BY refunded_at, id
                        """)
                .param("tenantId", tenantId).param("paymentId", paymentId).query((rs, row) -> new RefundResponse(
                        rs.getObject("id", UUID.class), rs.getString("refund_number"), rs.getLong("amount_minor"),
                        rs.getString("reason"), rs.getString("external_reference"), rs.getString("status"),
                        rs.getObject("refunded_at", OffsetDateTime.class))).list();
        return ResponseEntity.ok().eTag(VersionEtag.format(summary.version()))
                .body(new PaymentDetail(summary, allocations, refunds));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('payment.record')")
    public ResponseEntity<PaymentService.PaymentResult> record(Authentication authentication,
                                                               @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                               @Valid @RequestBody RecordPaymentRequest request,
                                                               HttpServletRequest servletRequest) {
        AuthenticatedUser user = principal(authentication);
        return idempotencyExecutor.execute(user.tenantId(), user.userId(), key, "POST",
                "/api/v1/payments", request,
                PaymentService.PaymentResult.class, () -> {
                    var result = paymentService.record(user.tenantId(), user.userId(), request.toCommand());
                    auditService.record(user.tenantId(), "USER", user.userId(), user.displayName(),
                            "payment.recorded", "payment", result.paymentId(), null, result,
                            request.reason(), servletRequest.getHeader("X-Request-Id"));
                    return ResponseEntity.status(201).body(result);
                });
    }

    @PostMapping("/{paymentId}/allocations")
    @PreAuthorize("hasAuthority('payment.record')")
    public ResponseEntity<PaymentService.AllocationResult> allocate(Authentication authentication,
                                                                   @PathVariable UUID paymentId,
                                                                   @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                                   @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                                   @Valid @RequestBody AllocateRequest request,
                                                                   HttpServletRequest servletRequest) {
        AuthenticatedUser user = principal(authentication);
        assertVersion(ifMatch, request.expectedPaymentVersion());
        String path = "/api/v1/payments/" + paymentId + "/allocations";
        return idempotencyExecutor.execute(user.tenantId(), user.userId(), key, "POST", path, request,
                PaymentService.AllocationResult.class, () -> {
                    var result = paymentService.allocate(user.tenantId(), user.userId(), paymentId,
                            new PaymentService.AllocatePayment(request.invoiceId(), request.amountMinor(),
                                    request.expectedPaymentVersion()));
                    auditService.record(user.tenantId(), "USER", user.userId(), user.displayName(),
                            "payment.allocated", "payment_allocation", result.allocationId(), null, result,
                            request.reason(), servletRequest.getHeader("X-Request-Id"));
                    return ResponseEntity.status(201).body(result);
                });
    }

    @PostMapping("/{paymentId}/allocations/{allocationId}/reverse")
    @PreAuthorize("hasAuthority('payment.record')")
    public ResponseEntity<PaymentService.AllocationResult> reverse(Authentication authentication,
                                                                  @PathVariable UUID paymentId,
                                                                  @PathVariable UUID allocationId,
                                                                  @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                                  @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                                  @Valid @RequestBody VersionedReasonRequest request,
                                                                  HttpServletRequest servletRequest) {
        AuthenticatedUser user = principal(authentication);
        assertVersion(ifMatch, request.expectedVersion());
        String path = "/api/v1/payments/" + paymentId + "/allocations/" + allocationId + "/reverse";
        return idempotencyExecutor.execute(user.tenantId(), user.userId(), key, "POST", path, request,
                PaymentService.AllocationResult.class, () -> {
                    var result = paymentService.reverseAllocation(user.tenantId(), user.userId(), paymentId,
                            allocationId, request.expectedVersion(), request.reason());
                    auditService.record(user.tenantId(), "USER", user.userId(), user.displayName(),
                            "payment.allocation_reversed", "payment_allocation", allocationId, null, result,
                            request.reason(), servletRequest.getHeader("X-Request-Id"));
                    return ResponseEntity.ok().body(result);
                });
    }

    @PostMapping("/{paymentId}/refunds")
    @PreAuthorize("hasAuthority('payment.record')")
    public ResponseEntity<PaymentService.RefundResult> refund(Authentication authentication,
                                                             @PathVariable UUID paymentId,
                                                             @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                             @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                             @Valid @RequestBody RefundRequest request,
                                                             HttpServletRequest servletRequest) {
        AuthenticatedUser user = principal(authentication);
        assertVersion(ifMatch, request.expectedVersion());
        String path = "/api/v1/payments/" + paymentId + "/refunds";
        return idempotencyExecutor.execute(user.tenantId(), user.userId(), key, "POST", path, request,
                PaymentService.RefundResult.class, () -> {
                    var result = paymentService.refund(user.tenantId(), user.userId(), paymentId,
                            request.expectedVersion(), request.amountMinor(), request.refundedAt(),
                            request.externalReference(), request.reason());
                    auditService.record(user.tenantId(), "USER", user.userId(), user.displayName(),
                            "payment.refunded", "payment_refund", result.refundId(), null, result,
                            request.reason(), servletRequest.getHeader("X-Request-Id"));
                    return ResponseEntity.status(201).body(result);
                });
    }

    private PaymentSummary map(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentSummary(rs.getObject("id", UUID.class), rs.getString("payment_number"),
                rs.getObject("customer_id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getString("currency_code"), rs.getLong("amount_minor"), rs.getLong("allocated_minor"),
                rs.getLong("refunded_minor"),
                rs.getString("payment_method"), rs.getObject("paid_at", OffsetDateTime.class),
                rs.getString("status"), rs.getString("external_reference"), rs.getLong("version"));
    }

    private AuthenticatedUser principal(Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    private void assertVersion(String ifMatch, long expectedVersion) {
        long headerVersion = VersionEtag.parse(ifMatch);
        if (headerVersion != expectedVersion) {
            throw new DomainException("VERSION_CONFLICT", "If-Match and expected_version must match", 409,
                    Map.of("if_match_version", headerVersion, "expected_version", expectedVersion));
        }
    }

    public record RecordPaymentRequest(@NotNull UUID customerId, UUID companyId,
                                       @NotBlank @Pattern(regexp = "[A-Z]{3}") String currencyCode,
                                       @Positive long amountMinor, @NotBlank String paymentMethod,
                                       @NotBlank String sourceSystem, String externalReference,
                                       @NotNull OffsetDateTime paidAt, String notes, @NotBlank String reason) {
        PaymentService.RecordPayment toCommand() {
            return new PaymentService.RecordPayment(customerId, companyId, currencyCode, amountMinor,
                    paymentMethod, sourceSystem, externalReference, paidAt, notes);
        }
    }

    public record AllocateRequest(@NotNull UUID invoiceId, @Positive long amountMinor,
                                  @PositiveOrZero long expectedPaymentVersion, @NotBlank String reason) {
    }

    public record VersionedReasonRequest(@PositiveOrZero long expectedVersion, @NotBlank String reason) {
    }

    public record RefundRequest(@PositiveOrZero long expectedVersion, @Positive long amountMinor,
                                @NotNull OffsetDateTime refundedAt, String externalReference,
                                @NotBlank String reason) {
    }

    public record PaymentSummary(UUID id, String paymentNumber, UUID customerId, UUID companyId,
                                 String currencyCode, long amountMinor, long allocatedMinor, long refundedMinor,
                                 String paymentMethod,
                                 OffsetDateTime paidAt, String status, String externalReference, long version) {
    }

    public record PaymentDetail(PaymentSummary payment, List<AllocationResponse> allocations,
                                List<RefundResponse> refunds) {
    }

    public record AllocationResponse(UUID id, UUID invoiceId, long amountMinor, String status,
                                     OffsetDateTime allocatedAt, OffsetDateTime reversedAt,
                                     String reversalReason) {
    }

    public record RefundResponse(UUID id, String refundNumber, long amountMinor, String reason,
                                 String externalReference, String status, OffsetDateTime refundedAt) {
    }
}
