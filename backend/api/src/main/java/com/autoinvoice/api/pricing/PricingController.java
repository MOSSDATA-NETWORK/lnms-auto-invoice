package com.autoinvoice.api.pricing;

import com.autoinvoice.api.http.VersionEtag;
import com.autoinvoice.api.idempotency.IdempotencyExecutor;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.billing.PricingTier;
import com.autoinvoice.billing.PricingVersionValidator;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.audit.AuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class PricingController {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final IdempotencyExecutor idempotency;
    private final AuditService audit;
    private final PricingVersionValidator validator = new PricingVersionValidator();

    public PricingController(JdbcClient jdbc, ObjectMapper objectMapper,
                             IdempotencyExecutor idempotency, AuditService audit) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @GetMapping("/pricing-rules")
    @PreAuthorize("hasAnyAuthority('customer.read','contract.write','pricing.publish')")
    public List<PricingRuleResponse> rules(Authentication authentication,
                                           @RequestParam(required = false) String status) {
        return jdbc.sql("SELECT * FROM pricing_rules WHERE tenant_id = :tenantId AND (:status IS NULL OR status = :status) ORDER BY rule_code")
                .param("tenantId", user(authentication).tenantId()).param("status", blank(status))
                .query(this::mapRule).list();
    }

    @GetMapping("/pricing-rules/{id}")
    @PreAuthorize("hasAnyAuthority('customer.read','contract.write','pricing.publish')")
    public ResponseEntity<PricingRuleDetail> rule(Authentication authentication, @PathVariable UUID id) {
        UUID tenantId = user(authentication).tenantId();
        PricingRuleResponse rule = findRule(tenantId, id);
        List<PricingVersionResponse> versions = jdbc.sql("""
                        SELECT * FROM pricing_rule_versions
                        WHERE tenant_id = :tenantId AND pricing_rule_id = :ruleId
                        ORDER BY version_no DESC
                        """)
                .param("tenantId", tenantId).param("ruleId", id).query(this::mapVersion).list().stream()
                .map(version -> withTiers(tenantId, version)).toList();
        return ResponseEntity.ok().eTag(VersionEtag.format(rule.version()))
                .body(new PricingRuleDetail(rule, versions));
    }

    @PostMapping("/pricing-rules")
    @PreAuthorize("hasAuthority('pricing.publish')")
    public ResponseEntity<PricingRuleResponse> createRule(Authentication authentication,
                                                          @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                          @Valid @RequestBody PricingRuleCreateRequest request,
                                                          HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST",
                "/api/v1/pricing-rules", request,
                PricingRuleResponse.class, () -> {
                    UUID id = UuidV7.generate();
                    jdbc.sql("""
                                    INSERT INTO pricing_rules(id, tenant_id, rule_code, rule_name, description)
                                    VALUES (:id, :tenantId, :code, :name, :description)
                                    """)
                            .param("id", id).param("tenantId", actor.tenantId()).param("code", request.ruleCode())
                            .param("name", request.ruleName()).param("description", request.description()).update();
                    PricingRuleResponse created = findRule(actor.tenantId(), id);
                    record(actor, "pricing.rule.created", "pricing_rule", id, null, created,
                            request.reason(), servletRequest);
                    return ResponseEntity.status(HttpStatus.CREATED).eTag(VersionEtag.format(0)).body(created);
                });
    }

    @PostMapping("/pricing-rules/{ruleId}/versions")
    @PreAuthorize("hasAuthority('pricing.publish')")
    public ResponseEntity<PricingVersionResponse> createVersion(Authentication authentication,
                                                                @PathVariable UUID ruleId,
                                                                @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                                @Valid @RequestBody PricingVersionCreateRequest request,
                                                                HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        String path = "/api/v1/pricing-rules/" + ruleId + "/versions";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                PricingVersionResponse.class, () -> createVersion(actor, ruleId, request, servletRequest));
    }

    @Transactional
    protected ResponseEntity<PricingVersionResponse> createVersion(AuthenticatedUser actor, UUID ruleId,
                                                                   PricingVersionCreateRequest request,
                                                                   HttpServletRequest servletRequest) {
        jdbc.sql("SELECT id FROM pricing_rules WHERE tenant_id = :tenantId AND id = :id FOR UPDATE")
                .param("tenantId", actor.tenantId()).param("id", ruleId).query(UUID.class).optional()
                .orElseThrow(() -> notFound("pricing_rule_id", ruleId));
        List<PricingTier> tiers = request.tiers().stream().map(TierRequest::domain).toList();
        validate(request, tiers);
        int versionNo = jdbc.sql("SELECT COALESCE(MAX(version_no), 0) + 1 FROM pricing_rule_versions WHERE pricing_rule_id = :ruleId")
                .param("ruleId", ruleId).query(Integer.class).single();
        UUID id = UuidV7.generate();
        jdbc.sql("""
                        INSERT INTO pricing_rule_versions(
                            id, tenant_id, pricing_rule_id, version_no, effective_from, effective_to,
                            billing_type, currency_code, unit, unit_price, base_fee, committed_quantity,
                            overage_unit_price, minimum_charge, maximum_charge, discount_rate, tax_rate,
                            rounding_mode, rounding_scale, config_json, change_note, created_by
                        ) VALUES (
                            :id, :tenantId, :ruleId, :versionNo, :effectiveFrom, :effectiveTo,
                            :billingType, :currency, :unit, :unitPrice, :baseFee, :committed,
                            :overage, :minimum, :maximum, :discount, :tax,
                            :roundingMode, :roundingScale, CAST(:config AS jsonb), :changeNote, :actorId
                        )
                        """)
                .param("id", id).param("tenantId", actor.tenantId()).param("ruleId", ruleId)
                .param("versionNo", versionNo).param("effectiveFrom", request.effectiveFrom())
                .param("effectiveTo", request.effectiveTo()).param("billingType", request.billingType())
                .param("currency", request.currencyCode()).param("unit", request.unit())
                .param("unitPrice", request.unitPrice()).param("baseFee", request.baseFee())
                .param("committed", request.committedQuantity()).param("overage", request.overageUnitPrice())
                .param("minimum", request.minimumCharge()).param("maximum", request.maximumCharge())
                .param("discount", request.discountRate()).param("tax", request.taxRate())
                .param("roundingMode", request.roundingMode()).param("roundingScale", request.roundingScale())
                .param("config", jsonText(request.config())).param("changeNote", request.changeNote())
                .param("actorId", actor.userId()).update();
        for (int index = 0; index < request.tiers().size(); index++) {
            TierRequest tier = request.tiers().get(index);
            jdbc.sql("""
                            INSERT INTO pricing_tiers(
                                id, tenant_id, pricing_rule_version_id, tier_no, lower_bound,
                                upper_bound, unit_price, pricing_mode
                            ) VALUES (:id, :tenantId, :versionId, :tierNo, :lower, :upper, :price, :mode)
                            """)
                    .param("id", UuidV7.generate()).param("tenantId", actor.tenantId()).param("versionId", id)
                    .param("tierNo", index + 1).param("lower", tier.lowerBound()).param("upper", tier.upperBound())
                    .param("price", tier.unitPrice()).param("mode", request.billingType()).update();
        }
        PricingVersionResponse created = findVersion(actor.tenantId(), id);
        record(actor, "pricing.version.created", "pricing_rule_version", id, null, created,
                request.reason(), servletRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/pricing-rule-versions/{id}/validate")
    @PreAuthorize("hasAuthority('pricing.publish')")
    public ValidationResponse validateVersion(Authentication authentication, @PathVariable UUID id) {
        UUID tenantId = user(authentication).tenantId();
        PricingVersionResponse version = findVersion(tenantId, id);
        validator.validate(definition(version));
        assertNoOverlap(tenantId, version);
        return new ValidationResponse(true, List.of());
    }

    @PostMapping("/pricing-rule-versions/{id}/publish")
    @PreAuthorize("hasAuthority('pricing.publish')")
    public ResponseEntity<PricingVersionResponse> publish(Authentication authentication, @PathVariable UUID id,
                                                          @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                          @Valid @RequestBody ReasonRequest request,
                                                          HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        String path = "/api/v1/pricing-rule-versions/" + id + "/publish";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                PricingVersionResponse.class, () -> publish(actor, id, request.reason(), servletRequest));
    }

    @Transactional
    protected ResponseEntity<PricingVersionResponse> publish(AuthenticatedUser actor, UUID id, String reason,
                                                             HttpServletRequest servletRequest) {
        PricingVersionResponse before = lockVersion(actor.tenantId(), id);
        if ("PUBLISHED".equals(before.status())) {
            activateRuleVersion(actor.tenantId(), before.pricingRuleId(), before.id());
            return ResponseEntity.ok(findVersion(actor.tenantId(), id));
        }
        if (!"DRAFT".equals(before.status())) {
            throw new DomainException("INVALID_PRICING_VERSION_STATUS", "Only a draft price version can be published", 409,
                    Map.of("status", before.status()));
        }
        validator.validate(definition(before));
        assertNoOverlap(actor.tenantId(), before);
        jdbc.sql("""
                        UPDATE pricing_rule_versions SET status = 'PUBLISHED', published_at = now()
                        WHERE tenant_id = :tenantId AND id = :id AND status = 'DRAFT'
                """)
                .param("tenantId", actor.tenantId()).param("id", id).update();
        activateRuleVersion(actor.tenantId(), before.pricingRuleId(), before.id());
        PricingVersionResponse after = findVersion(actor.tenantId(), id);
        record(actor, "pricing.version.published", "pricing_rule_version", id, before, after, reason, servletRequest);
        return ResponseEntity.ok(after);
    }

    private void activateRuleVersion(UUID tenantId, UUID ruleId, UUID versionId) {
        jdbc.sql("""
                        UPDATE pricing_rules
                        SET current_version_id = :versionId, status = 'ACTIVE',
                            updated_at = now(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :ruleId
                          AND (current_version_id IS DISTINCT FROM :versionId OR status <> 'ACTIVE')
                        """)
                .param("versionId", versionId).param("tenantId", tenantId).param("ruleId", ruleId).update();
    }

    @PostMapping("/pricing-rule-versions/{id}/retire")
    @PreAuthorize("hasAuthority('pricing.publish')")
    public ResponseEntity<PricingVersionResponse> retire(Authentication authentication, @PathVariable UUID id,
                                                         @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                         @Valid @RequestBody ReasonRequest request,
                                                         HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        String path = "/api/v1/pricing-rule-versions/" + id + "/retire";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                PricingVersionResponse.class, () -> {
                    PricingVersionResponse before = findVersion(actor.tenantId(), id);
                    int changed = jdbc.sql("""
                                    UPDATE pricing_rule_versions SET status = 'RETIRED'
                                    WHERE tenant_id = :tenantId AND id = :id AND status = 'PUBLISHED'
                                    """)
                            .param("tenantId", actor.tenantId()).param("id", id).update();
                    if (changed != 1 && !"RETIRED".equals(before.status())) {
                        throw new DomainException("INVALID_PRICING_VERSION_STATUS",
                                "Only a published price version can be retired", 409, Map.of("status", before.status()));
                    }
                    PricingVersionResponse after = findVersion(actor.tenantId(), id);
                    record(actor, "pricing.version.retired", "pricing_rule_version", id, before, after,
                            request.reason(), servletRequest);
                    return ResponseEntity.ok(after);
                });
    }

    private void validate(PricingVersionCreateRequest request, List<PricingTier> tiers) {
        validator.validate(new PricingVersionValidator.PricingVersionDefinition(
                request.billingType(), request.effectiveFrom(), request.effectiveTo(), request.unitPrice(),
                request.baseFee(), request.committedQuantity(), request.overageUnitPrice(),
                request.minimumCharge(), request.maximumCharge(), request.discountRate(), request.taxRate(),
                request.roundingMode(), request.roundingScale(), decimal(request.config(), "rounding_step"), tiers));
    }

    private PricingVersionValidator.PricingVersionDefinition definition(PricingVersionResponse version) {
        return new PricingVersionValidator.PricingVersionDefinition(version.billingType(), version.effectiveFrom(),
                version.effectiveTo(), version.unitPrice(), version.baseFee(), version.committedQuantity(),
                version.overageUnitPrice(), version.minimumCharge(), version.maximumCharge(), version.discountRate(),
                version.taxRate(), version.roundingMode(), version.roundingScale(),
                decimal(version.config(), "rounding_step"), version.tiers());
    }

    private void assertNoOverlap(UUID tenantId, PricingVersionResponse version) {
        boolean overlap = jdbc.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM pricing_rule_versions
                            WHERE tenant_id = :tenantId AND pricing_rule_id = :ruleId
                              AND id <> :id AND status = 'PUBLISHED'
                              AND tstzrange(effective_from, COALESCE(effective_to, 'infinity'::timestamptz), '[)')
                                  && tstzrange(:effectiveFrom, COALESCE(:effectiveTo, 'infinity'::timestamptz), '[)')
                        )
                        """)
                .param("tenantId", tenantId).param("ruleId", version.pricingRuleId()).param("id", version.id())
                .param("effectiveFrom", version.effectiveFrom()).param("effectiveTo", version.effectiveTo())
                .query(Boolean.class).single();
        if (overlap) {
            throw new DomainException("PRICING_VERSION_OVERLAP",
                    "Published price version effective periods cannot overlap", 409,
                    Map.of("pricing_rule_id", version.pricingRuleId()));
        }
    }

    private PricingRuleResponse findRule(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM pricing_rules WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::mapRule).optional()
                .orElseThrow(() -> notFound("pricing_rule_id", id));
    }

    private PricingVersionResponse findVersion(UUID tenantId, UUID id) {
        PricingVersionResponse version = jdbc.sql("SELECT * FROM pricing_rule_versions WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::mapVersion).optional()
                .orElseThrow(() -> notFound("pricing_rule_version_id", id));
        return withTiers(tenantId, version);
    }

    private PricingVersionResponse lockVersion(UUID tenantId, UUID id) {
        PricingVersionResponse version = jdbc.sql("SELECT * FROM pricing_rule_versions WHERE tenant_id = :tenantId AND id = :id FOR UPDATE")
                .param("tenantId", tenantId).param("id", id).query(this::mapVersion).optional()
                .orElseThrow(() -> notFound("pricing_rule_version_id", id));
        return withTiers(tenantId, version);
    }

    private PricingRuleResponse mapRule(ResultSet rs, int row) throws SQLException {
        return new PricingRuleResponse(rs.getObject("id", UUID.class), rs.getString("rule_code"),
                rs.getString("rule_name"), rs.getString("description"), rs.getString("status"),
                rs.getObject("current_version_id", UUID.class), rs.getLong("version"));
    }

    private PricingVersionResponse mapVersion(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new PricingVersionResponse(id, rs.getObject("pricing_rule_id", UUID.class), rs.getInt("version_no"),
                rs.getObject("effective_from", OffsetDateTime.class), rs.getObject("effective_to", OffsetDateTime.class),
                rs.getString("billing_type"), rs.getString("currency_code"), rs.getString("unit"),
                rs.getBigDecimal("unit_price"), rs.getBigDecimal("base_fee"), rs.getBigDecimal("committed_quantity"),
                rs.getBigDecimal("overage_unit_price"), rs.getBigDecimal("minimum_charge"),
                rs.getBigDecimal("maximum_charge"), rs.getBigDecimal("discount_rate"), rs.getBigDecimal("tax_rate"),
                rs.getString("rounding_mode"), rs.getObject("rounding_scale", Integer.class),
                json(rs.getString("config_json")), List.of(), rs.getString("status"), rs.getString("change_note"),
                rs.getObject("published_at", OffsetDateTime.class));
    }

    private PricingVersionResponse withTiers(UUID tenantId, PricingVersionResponse version) {
        List<PricingTier> tiers = jdbc.sql("""
                        SELECT lower_bound, upper_bound, unit_price FROM pricing_tiers
                        WHERE tenant_id = :tenantId AND pricing_rule_version_id = :versionId ORDER BY tier_no
                        """)
                .param("tenantId", tenantId).param("versionId", version.id())
                .query((rs, row) -> new PricingTier(rs.getBigDecimal("lower_bound"),
                        rs.getBigDecimal("upper_bound"), rs.getBigDecimal("unit_price"))).list();
        return new PricingVersionResponse(version.id(), version.pricingRuleId(), version.versionNo(),
                version.effectiveFrom(), version.effectiveTo(), version.billingType(), version.currencyCode(),
                version.unit(), version.unitPrice(), version.baseFee(), version.committedQuantity(),
                version.overageUnitPrice(), version.minimumCharge(), version.maximumCharge(),
                version.discountRate(), version.taxRate(), version.roundingMode(), version.roundingScale(),
                version.config(), tiers, version.status(), version.changeNote(), version.publishedAt());
    }

    private BigDecimal decimal(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field) || node.path(field).asText().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(node.path(field).asText());
        } catch (NumberFormatException exception) {
            throw new DomainException("PRICING_VERSION_INVALID", field + " must be a decimal string", 422,
                    Map.of("field", field));
        }
    }

    private JsonNode json(String value) throws SQLException {
        try {
            return objectMapper.readTree(value == null ? "{}" : value);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid pricing JSON", exception);
        }
    }

    private String jsonText(JsonNode value) {
        return value == null || value.isNull() ? "{}" : value.toString();
    }

    private AuthenticatedUser user(Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    private void record(AuthenticatedUser actor, String action, String type, UUID id, Object before,
                        Object after, String reason, HttpServletRequest request) {
        audit.record(actor.tenantId(), "USER", actor.userId(), actor.displayName(), action, type, id,
                before, after, reason, request.getHeader("X-Request-Id"));
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private DomainException notFound(String field, UUID id) {
        return new DomainException("RESOURCE_NOT_FOUND", "Resource was not found", 404, Map.of(field, id));
    }

    public record PricingRuleCreateRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{2,79}") String ruleCode,
            @NotBlank String ruleName, String description, @NotBlank String reason) {
    }

    public record PricingVersionCreateRequest(@NotNull OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo,
                                              @NotBlank String billingType,
                                              @NotBlank @Pattern(regexp = "[A-Z]{3}") String currencyCode,
                                              String unit, BigDecimal unitPrice, BigDecimal baseFee,
                                              BigDecimal committedQuantity, BigDecimal overageUnitPrice,
                                              BigDecimal minimumCharge, BigDecimal maximumCharge,
                                              BigDecimal discountRate, BigDecimal taxRate,
                                              @NotBlank String roundingMode, Integer roundingScale,
                                              JsonNode config, List<@Valid @NotNull TierRequest> tiers,
                                              String changeNote,
                                              @NotBlank String reason) {
        public PricingVersionCreateRequest {
            tiers = tiers == null ? List.of() : List.copyOf(tiers);
        }
    }

    public record TierRequest(@NotNull BigDecimal lowerBound, BigDecimal upperBound,
                              @NotNull BigDecimal unitPrice) {
        PricingTier domain() {
            try {
                return new PricingTier(lowerBound, upperBound, unitPrice);
            } catch (IllegalArgumentException exception) {
                throw new DomainException("PRICING_VERSION_INVALID", exception.getMessage(), 422, Map.of());
            }
        }
    }

    public record PricingRuleResponse(UUID id, String ruleCode, String ruleName, String description,
                                      String status, UUID currentVersionId, long version) {
    }

    public record PricingRuleDetail(PricingRuleResponse rule, List<PricingVersionResponse> versions) {
    }

    public record PricingVersionResponse(UUID id, UUID pricingRuleId, int versionNo,
                                         OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo,
                                         String billingType, String currencyCode, String unit,
                                         BigDecimal unitPrice, BigDecimal baseFee, BigDecimal committedQuantity,
                                         BigDecimal overageUnitPrice, BigDecimal minimumCharge,
                                         BigDecimal maximumCharge, BigDecimal discountRate, BigDecimal taxRate,
                                         String roundingMode, Integer roundingScale, JsonNode config,
                                         List<PricingTier> tiers, String status, String changeNote,
                                         OffsetDateTime publishedAt) {
    }

    public record ValidationResponse(boolean valid, List<String> errors) {
    }

    public record ReasonRequest(@NotBlank String reason) {
    }
}
