package com.autoinvoice.api.customer;

import com.autoinvoice.api.idempotency.IdempotencyExecutor;
import com.autoinvoice.api.http.VersionEtag;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {
    private final JdbcClient jdbc;
    private final AuditService auditService;
    private final IdempotencyExecutor idempotencyExecutor;

    public CustomerController(JdbcClient jdbc, AuditService auditService, IdempotencyExecutor idempotencyExecutor) {
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.idempotencyExecutor = idempotencyExecutor;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('customer.read')")
    public CursorPage<CustomerResponse> list(Authentication authentication,
                                             @RequestParam(defaultValue = "50") int limit,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String q) {
        AuthenticatedUser user = principal(authentication);
        int pageSize = Math.max(1, Math.min(limit, 200));
        UUID after = decodeCursor(cursor);
        List<CustomerResponse> rows = jdbc.sql("""
                        SELECT * FROM customers
                        WHERE tenant_id = :tenantId
                          AND (:afterId IS NULL OR id > :afterId)
                          AND (:status IS NULL OR status = :status)
                          AND (:query IS NULL OR customer_no ILIKE :likeQuery OR customer_name ILIKE :likeQuery)
                        ORDER BY id
                        LIMIT :fetchLimit
                        """)
                .param("tenantId", user.tenantId())
                .param("afterId", after)
                .param("status", blankToNull(status))
                .param("query", blankToNull(q))
                .param("likeQuery", q == null ? null : "%" + q.trim() + "%")
                .param("fetchLimit", pageSize + 1)
                .query(this::mapCustomer)
                .list();
        boolean hasMore = rows.size() > pageSize;
        List<CustomerResponse> data = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore ? encodeCursor(data.getLast().id()) : null;
        return new CursorPage<>(data, new PageInfo(nextCursor, hasMore));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('customer.read')")
    public ResponseEntity<CustomerResponse> get(Authentication authentication, @PathVariable UUID id) {
        CustomerResponse customer = find(principal(authentication).tenantId(), id);
        return ResponseEntity.ok().eTag(VersionEtag.format(customer.version())).body(customer);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('customer.write')")
    public ResponseEntity<CustomerResponse> create(Authentication authentication,
                                                   @RequestHeader(IdempotencyExecutor.HEADER) String idempotencyKey,
                                                   @Valid @RequestBody CustomerCreateRequest request,
                                                   HttpServletRequest servletRequest) {
        AuthenticatedUser user = principal(authentication);
        return idempotencyExecutor.execute(user.tenantId(), user.userId(), idempotencyKey, "POST",
                "/api/v1/customers",
                request, CustomerResponse.class, () -> {
                    UUID id = UuidV7.generate();
                    jdbc.sql("""
                                    INSERT INTO customers(
                                        id, tenant_id, customer_no, customer_name, customer_type, owner_user_id,
                                        default_currency, default_language, default_billing_cycle,
                                        default_payment_terms_days, status, notes
                                    ) VALUES (
                                        :id, :tenantId, :customerNo, :customerName, :customerType, :ownerUserId,
                                        :currency, :language, :billingCycle, :paymentTerms, 'ACTIVE', :notes
                                    )
                                    """)
                            .param("id", id)
                            .param("tenantId", user.tenantId())
                            .param("customerNo", request.customerNo())
                            .param("customerName", request.customerName())
                            .param("customerType", request.customerType())
                            .param("ownerUserId", user.userId())
                            .param("currency", request.defaultCurrency())
                            .param("language", request.defaultLanguage())
                            .param("billingCycle", request.defaultBillingCycle())
                            .param("paymentTerms", request.defaultPaymentTermsDays())
                            .param("notes", request.notes())
                            .update();
                    CustomerResponse created = find(user.tenantId(), id);
                    auditService.record(user.tenantId(), "USER", user.userId(), user.displayName(),
                            "customer.created", "customer", id, null, created, request.reason(), requestId(servletRequest));
                    return ResponseEntity.status(HttpStatus.CREATED).body(created);
                });
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('customer.write')")
    @Transactional
    public ResponseEntity<CustomerResponse> update(Authentication authentication, @PathVariable UUID id,
                                                    @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                    @Valid @RequestBody CustomerUpdateRequest request,
                                                    HttpServletRequest servletRequest) {
        AuthenticatedUser user = principal(authentication);
        CustomerResponse before = find(user.tenantId(), id);
        long expectedVersion = VersionEtag.parse(ifMatch);
        int updated = jdbc.sql("""
                        UPDATE customers
                        SET customer_name = COALESCE(:customerName, customer_name),
                            default_currency = COALESCE(:currency, default_currency),
                            default_language = COALESCE(:language, default_language),
                            default_payment_terms_days = COALESCE(:paymentTerms, default_payment_terms_days),
                            notes = COALESCE(:notes, notes), updated_at = now(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                        """)
                .param("customerName", request.customerName())
                .param("currency", request.defaultCurrency())
                .param("language", request.defaultLanguage())
                .param("paymentTerms", request.defaultPaymentTermsDays())
                .param("notes", request.notes())
                .param("tenantId", user.tenantId())
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update();
        if (updated != 1) {
            throw new DomainException("VERSION_CONFLICT", "Customer was modified by another request", 409,
                    java.util.Map.of("expected_version", expectedVersion));
        }
        CustomerResponse after = find(user.tenantId(), id);
        auditService.record(user.tenantId(), "USER", user.userId(), user.displayName(),
                "customer.updated", "customer", id, before, after, request.reason(), requestId(servletRequest));
        return ResponseEntity.ok().eTag(VersionEtag.format(after.version())).body(after);
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('customer.write')")
    @Transactional
    public ResponseEntity<Void> archive(Authentication authentication, @PathVariable UUID id,
                                        @RequestHeader(IdempotencyExecutor.HEADER) String idempotencyKey,
                                        @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                        @RequestBody(required = false) ArchiveRequest request,
                                        HttpServletRequest servletRequest) {
        AuthenticatedUser user = principal(authentication);
        long expectedVersion = VersionEtag.parse(ifMatch);
        ArchiveCommand command = new ArchiveCommand(expectedVersion, request == null ? null : request.reason());
        String path = "/api/v1/customers/" + id + "/archive";
        return idempotencyExecutor.execute(user.tenantId(), user.userId(), idempotencyKey, "POST", path,
                command, Void.class, () -> {
                    CustomerResponse before = find(user.tenantId(), id);
                    int updated = jdbc.sql("""
                                    UPDATE customers SET status = 'ARCHIVED', version = version + 1, updated_at = now()
                                    WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                                    """)
                            .param("tenantId", user.tenantId())
                            .param("id", id)
                            .param("expectedVersion", expectedVersion)
                            .update();
                    if (updated != 1) {
                        throw new DomainException("VERSION_CONFLICT", "Customer was modified by another request", 409,
                                java.util.Map.of("expected_version", expectedVersion));
                    }
                    CustomerResponse after = find(user.tenantId(), id);
                    auditService.record(user.tenantId(), "USER", user.userId(), user.displayName(),
                            "customer.archived", "customer", id, before, after,
                            command.reason(), requestId(servletRequest));
                    return ResponseEntity.noContent().eTag(VersionEtag.format(after.version())).build();
                });
    }

    private CustomerResponse find(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM customers WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", id)
                .query(this::mapCustomer)
                .optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Customer was not found", 404,
                        java.util.Map.of("customer_id", id)));
    }

    private CustomerResponse mapCustomer(ResultSet rs, int rowNum) throws SQLException {
        return new CustomerResponse(
                rs.getObject("id", UUID.class),
                rs.getString("customer_no"),
                rs.getString("customer_name"),
                rs.getString("customer_type"),
                rs.getString("default_currency"),
                rs.getString("default_language"),
                rs.getString("default_billing_cycle"),
                rs.getInt("default_payment_terms_days"),
                rs.getString("status"),
                rs.getString("notes"),
                rs.getLong("version")
        );
    }

    private AuthenticatedUser principal(Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String requestId(HttpServletRequest request) {
        return request.getHeader("X-Request-Id");
    }

    private String encodeCursor(UUID id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(id.toString().getBytes(StandardCharsets.UTF_8));
    }

    private UUID decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            throw new DomainException("VALIDATION_FAILED", "Cursor is invalid", 400, java.util.Map.of());
        }
    }

    public record CustomerCreateRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9-]{2,63}") String customerNo,
            @NotBlank String customerName,
            @NotBlank String customerType,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String defaultCurrency,
            @NotBlank String defaultLanguage,
            @NotBlank String defaultBillingCycle,
            @PositiveOrZero int defaultPaymentTermsDays,
            String notes,
            @NotBlank String reason
    ) {
    }

    public record CustomerUpdateRequest(
            String customerName,
            @Pattern(regexp = "[A-Z]{3}") String defaultCurrency,
            String defaultLanguage,
            @PositiveOrZero Integer defaultPaymentTermsDays,
            String notes,
            @NotBlank String reason
    ) {
    }

    public record ArchiveRequest(String reason) {
    }

    private record ArchiveCommand(long expectedVersion, String reason) {
    }

    public record CustomerResponse(
            UUID id,
            String customerNo,
            String customerName,
            String customerType,
            String defaultCurrency,
            String defaultLanguage,
            String defaultBillingCycle,
            int defaultPaymentTermsDays,
            String status,
            String notes,
            long version
    ) {
    }

    public record CursorPage<T>(List<T> data, PageInfo page) {
    }

    public record PageInfo(String nextCursor, boolean hasMore) {
    }
}
