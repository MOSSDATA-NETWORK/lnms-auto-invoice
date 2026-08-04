package com.autoinvoice.invoice;

import com.autoinvoice.billing.BillingEngine;
import com.autoinvoice.billing.BillingRequest;
import com.autoinvoice.billing.BillingResult;
import com.autoinvoice.billing.BillingType;
import com.autoinvoice.billing.PricingTier;
import com.autoinvoice.billing.ProrationMode;
import com.autoinvoice.billing.RoundingRule;
import com.autoinvoice.billing.UsageRoundingMode;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class InvoicePreviewGenerationService {
    private static final DateTimeFormatter PERIOD_KEY = DateTimeFormatter.ofPattern("yyyyMM");
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final BillingEngine billingEngine = new BillingEngine();

    public InvoicePreviewGenerationService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public GenerationResult generate(UUID tenantId, UUID profileId, OffsetDateTime periodStart,
                                     OffsetDateTime periodEnd, UUID requestedBy, UUID generationId) {
        requirePeriod(periodStart, periodEnd);
        Profile profile = loadProfile(tenantId, profileId);
        String previewNumber = previewNumber(profile, periodStart, generationId);
        ExistingPreview existing = findByNumber(tenantId, previewNumber);
        if (existing != null) {
            return new GenerationResult(existing.id(), existing.previewNumber(), existing.status(),
                    existing.version(), false);
        }
        List<GeneratedLine> lines = calculateLines(tenantId, profile, periodStart, periodEnd);
        UUID previewId = UuidV7.generate();
        LocalDate issueDate = periodEnd.atZoneSameInstant(ZoneId.of(profile.timezone())).toLocalDate();
        LocalDate dueDate = issueDate.plusDays(profile.paymentTermsDays());
        Totals totals = totals(lines, Set.of(), List.of());
        ObjectNode renderModel = renderModel(previewNumber, profile, periodStart, periodEnd,
                issueDate, dueDate, lines, Set.of(), List.of(), totals);
        String calculationHash = sha256(renderModel.toString());

        jdbc.sql("""
                        INSERT INTO invoice_previews(
                            id, tenant_id, preview_number, invoice_profile_id, customer_id, company_id,
                            template_id, template_version_id, approval_workflow_version_id,
                            period_start, period_end, issue_date, due_date, timezone, language, currency_code,
                            subtotal_minor, discount_minor, tax_minor, adjustment_minor, total_minor,
                            profile_snapshot_json, party_snapshot_json, render_model_json, calculation_hash,
                            status, generated_at, created_by
                        ) VALUES (
                            :id, :tenantId, :previewNumber, :profileId, :customerId, :companyId,
                            :templateId, :templateVersionId, :workflowVersionId,
                            :periodStart, :periodEnd, :issueDate, :dueDate, :timezone, :language, :currency,
                            :subtotal, :discount, :tax, :adjustment, :total,
                            CAST(:profileSnapshot AS jsonb), CAST(:partySnapshot AS jsonb),
                            CAST(:renderModel AS jsonb), :calculationHash, 'GENERATING', now(), :requestedBy
                        )
                        """)
                .param("id", previewId).param("tenantId", tenantId).param("previewNumber", previewNumber)
                .param("profileId", profile.id()).param("customerId", profile.customerId())
                .param("companyId", profile.companyId()).param("templateId", profile.templateId())
                .param("templateVersionId", profile.templateVersionId())
                .param("workflowVersionId", profile.workflowVersionId()).param("periodStart", periodStart)
                .param("periodEnd", periodEnd).param("issueDate", issueDate).param("dueDate", dueDate)
                .param("timezone", profile.timezone()).param("language", profile.language())
                .param("currency", profile.currency()).param("subtotal", totals.subtotalMinor())
                .param("discount", totals.discountMinor()).param("tax", totals.taxMinor())
                .param("adjustment", totals.adjustmentMinor()).param("total", totals.totalMinor())
                .param("profileSnapshot", profile.profileSnapshot()).param("partySnapshot", profile.partySnapshot())
                .param("renderModel", renderModel.toString()).param("calculationHash", calculationHash)
                .param("requestedBy", requestedBy).update();
        insertLines(tenantId, previewId, lines);
        jdbc.sql("""
                        UPDATE invoice_previews SET status = 'DRAFT', updated_at = now()
                        WHERE tenant_id = :tenantId AND id = :previewId AND status = 'GENERATING'
                        """)
                .param("tenantId", tenantId).param("previewId", previewId).update();
        return new GenerationResult(previewId, previewNumber, "DRAFT", 0, profile.autoSubmitReview());
    }

    @Transactional
    public GenerationResult recalculate(UUID tenantId, UUID previewId, long expectedVersion,
                                        UUID requestedBy) {
        EditablePreview preview = lockEditablePreview(tenantId, previewId, expectedVersion);
        Profile profile = loadProfile(tenantId, preview.profileId());
        Set<String> excludedSourceKeys = jdbc.sql("""
                        SELECT item.source_key
                        FROM invoice_preview_exclusions exclusion
                        JOIN invoice_preview_items item ON item.id = exclusion.invoice_preview_item_id
                        WHERE exclusion.tenant_id = :tenantId AND exclusion.invoice_preview_id = :previewId
                        """)
                .param("tenantId", tenantId).param("previewId", previewId).query(String.class)
                .list().stream().collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Adjustment> adjustments = loadAdjustments(tenantId, previewId);
        List<GeneratedLine> lines = calculateLines(tenantId, profile, preview.periodStart(), preview.periodEnd());
        Set<String> survivingExclusions = new LinkedHashSet<>();
        Set<String> newKeys = lines.stream().map(GeneratedLine::sourceKey)
                .collect(java.util.stream.Collectors.toSet());
        excludedSourceKeys.stream().filter(newKeys::contains).forEach(survivingExclusions::add);
        Totals totals = totals(lines, survivingExclusions, adjustments);
        ObjectNode renderModel = renderModel(preview.previewNumber(), profile, preview.periodStart(),
                preview.periodEnd(), preview.issueDate(), preview.dueDate(), lines, survivingExclusions,
                adjustments, totals);
        String calculationHash = sha256(renderModel.toString());

        invalidateApproval(tenantId, previewId, requestedBy, preview.version(), "Preview recalculated");
        jdbc.sql("DELETE FROM invoice_preview_items WHERE tenant_id = :tenantId AND invoice_preview_id = :previewId")
                .param("tenantId", tenantId).param("previewId", previewId).update();
        insertLines(tenantId, previewId, lines);
        reapplyExclusions(tenantId, previewId, survivingExclusions, requestedBy);
        int changed = jdbc.sql("""
                        UPDATE invoice_previews
                        SET template_id = :templateId, template_version_id = :templateVersionId,
                            approval_workflow_version_id = :workflowVersionId,
                            subtotal_minor = :subtotal, discount_minor = :discount, tax_minor = :tax,
                            adjustment_minor = :adjustment, total_minor = :total,
                            profile_snapshot_json = CAST(:profileSnapshot AS jsonb),
                            party_snapshot_json = CAST(:partySnapshot AS jsonb),
                            render_model_json = CAST(:renderModel AS jsonb), calculation_hash = :calculationHash,
                            status = 'DRAFT', approved_at = NULL, generated_at = now(), updated_at = now(),
                            version = version + 1
                        WHERE tenant_id = :tenantId AND id = :previewId AND version = :expectedVersion
                        """)
                .param("templateId", profile.templateId()).param("templateVersionId", profile.templateVersionId())
                .param("workflowVersionId", profile.workflowVersionId()).param("subtotal", totals.subtotalMinor())
                .param("discount", totals.discountMinor()).param("tax", totals.taxMinor())
                .param("adjustment", totals.adjustmentMinor()).param("total", totals.totalMinor())
                .param("profileSnapshot", profile.profileSnapshot()).param("partySnapshot", profile.partySnapshot())
                .param("renderModel", renderModel.toString()).param("calculationHash", calculationHash)
                .param("tenantId", tenantId).param("previewId", previewId)
                .param("expectedVersion", expectedVersion).update();
        if (changed != 1) {
            throw versionConflict(expectedVersion);
        }
        return new GenerationResult(previewId, preview.previewNumber(), "DRAFT", expectedVersion + 1, false);
    }

    private List<GeneratedLine> calculateLines(UUID tenantId, Profile profile, OffsetDateTime periodStart,
                                               OffsetDateTime periodEnd) {
        List<Assignment> assignments = loadAssignments(tenantId, profile.id(), periodStart, periodEnd);
        if (assignments.isEmpty()) {
            throw new DomainException("INVOICE_PROFILE_EMPTY",
                    "No active contract item assignment covers the billing period", 422,
                    Map.of("invoice_profile_id", profile.id()));
        }
        ZoneId zone = ZoneId.of(profile.timezone());
        List<GeneratedLine> lines = new ArrayList<>();
        int lineNo = 1;
        for (Assignment assignment : assignments) {
            OffsetDateTime activeStart = maximum(periodStart, assignment.assignmentFrom(), assignment.itemFrom(),
                    atStart(assignment.contractFrom(), zone), atStart(assignment.serviceFrom(), zone));
            OffsetDateTime activeEnd = minimum(periodEnd, assignment.assignmentTo(), assignment.itemTo(),
                    atStart(assignment.contractTo(), zone), afterServiceEnd(assignment.serviceTo(), zone));
            if (!activeStart.isBefore(activeEnd)) {
                continue;
            }
            List<PriceVersion> versions = loadPriceVersions(tenantId, assignment.pricingRuleId(), activeStart, activeEnd);
            OffsetDateTime cursor = activeStart;
            for (PriceVersion price : versions) {
                OffsetDateTime segmentStart = maximum(activeStart, price.effectiveFrom(), cursor);
                OffsetDateTime segmentEnd = minimum(activeEnd, price.effectiveTo());
                if (segmentStart.isAfter(cursor)) {
                    throw pricingGap(assignment, cursor, segmentStart);
                }
                if (!segmentStart.isBefore(segmentEnd)) {
                    continue;
                }
                GeneratedLine line = calculateLine(tenantId, profile, assignment, price, periodStart,
                        periodEnd, segmentStart, segmentEnd, lineNo++);
                lines.add(line);
                cursor = segmentEnd;
                if (!cursor.isBefore(activeEnd)) {
                    break;
                }
            }
            if (cursor.isBefore(activeEnd)) {
                throw pricingGap(assignment, cursor, activeEnd);
            }
        }
        if (lines.isEmpty()) {
            throw new DomainException("INVOICE_PROFILE_EMPTY",
                    "Assignments exist but no chargeable interval covers the billing period", 422,
                    Map.of("invoice_profile_id", profile.id()));
        }
        return List.copyOf(lines);
    }

    private GeneratedLine calculateLine(UUID tenantId, Profile profile, Assignment assignment, PriceVersion price,
                                        OffsetDateTime periodStart, OffsetDateTime periodEnd,
                                        OffsetDateTime segmentStart, OffsetDateTime segmentEnd, int lineNo) {
        if (!price.currency().equals(profile.currency())) {
            throw new DomainException("BILLING_CURRENCY_MISMATCH",
                    "Pricing version currency does not match the invoice profile", 422,
                    Map.of("contract_item_id", assignment.contractItemId(), "pricing_currency", price.currency(),
                            "invoice_currency", profile.currency()));
        }
        if (!assignment.billingType().equals(price.billingType())) {
            throw new DomainException("BILLING_TYPE_MISMATCH",
                    "Contract item and pricing version billing types do not match", 422,
                    Map.of("contract_item_id", assignment.contractItemId(), "contract_item_type",
                            assignment.billingType(), "pricing_type", price.billingType()));
        }
        BillingType billingType;
        try {
            billingType = BillingType.valueOf(price.billingType());
        } catch (IllegalArgumentException exception) {
            throw new DomainException("UNSUPPORTED_BILLING_TYPE", "Unsupported billing type", 422,
                    Map.of("billing_type", price.billingType()));
        }
        Usage usage = requiresUsage(billingType)
                ? loadUsage(tenantId, assignment.contractItemId(), segmentStart, segmentEnd)
                : null;
        BigDecimal rawUsage = usage == null ? null
                : usage.convertedUsage() == null ? usage.rawUsage() : usage.convertedUsage();
        if (usage != null && rawUsage == null) {
            throw new DomainException("USAGE_VALUE_MISSING",
                    "The usage snapshot does not contain a billable usage value", 422,
                    Map.of("usage_snapshot_id", usage.id(), "contract_item_id", assignment.contractItemId()));
        }
        JsonNode config = price.config();
        ProrationMode proration = enumValue(ProrationMode.class, text(config, "proration_mode", "ACTUAL_DAYS"),
                "proration_mode");
        UsageRoundingMode roundingMode = enumValue(UsageRoundingMode.class, price.roundingMode(), "rounding_mode");
        RoundingRule rounding = new RoundingRule(roundingMode, price.roundingScale(), decimal(config, "rounding_step"));
        BigDecimal taxRate = price.taxRate() == null ? assignment.taxRate() : price.taxRate();
        ZoneId zone = ZoneId.of(profile.timezone());
        BillingRequest request = new BillingRequest(
                billingType,
                periodStart.atZoneSameInstant(zone).toLocalDate(),
                periodEnd.atZoneSameInstant(zone).toLocalDate(),
                segmentStart.atZoneSameInstant(zone).toLocalDate(),
                segmentEnd.atZoneSameInstant(zone).toLocalDate(),
                proration, profile.currency(), profile.minorUnit(), rawUsage, rounding,
                assignment.defaultQuantity(), price.baseFee(), price.unitPrice(), price.committedQuantity(),
                price.overageUnitPrice(), decimal(config, "free_allowance"), price.minimumCharge(),
                price.maximumCharge(), price.discountRate(), taxRate, assignment.taxInclusive(), price.tiers());
        BillingResult result = billingEngine.calculate(request);
        AllocatedAmounts amounts = allocate(result, assignment.mode(), assignment.allocationValue(),
                profile.minorUnit(), assignment.contractItemId());
        ObjectNode calculation = objectMapper.valueToTree(result.calculationSnapshot());
        calculation.put("pricing_rule_version_id", price.id().toString());
        calculation.put("assignment_mode", assignment.mode().name());
        if (assignment.allocationValue() != null) {
            calculation.put("allocation_value", assignment.allocationValue().toPlainString());
        }
        calculation.put("source_subtotal_minor", result.subtotalMinor());
        calculation.put("source_discount_minor", result.discountMinor());
        calculation.put("source_tax_minor", result.taxMinor());
        calculation.put("source_total_minor", result.totalMinor());
        if (usage != null) {
            calculation.put("usage_snapshot_id", usage.id().toString());
            calculation.put("usage_data_hash", usage.dataHash());
        }
        ObjectNode display = objectMapper.createObjectNode()
                .put("assignment_mode", assignment.mode().name())
                .put("display_only", assignment.mode() == AssignmentMode.DISPLAY_ONLY);
        String sourceKey = assignment.contractItemId() + ":" + price.id() + ":" + segmentStart.toInstant();
        return new GeneratedLine(assignment.contractItemId(), assignment.serviceId(), price.id(),
                usage == null ? null : usage.id(), sourceKey, lineNo, assignment.itemName(),
                segmentStart, segmentEnd, rawUsage, rawUsage, result.roundedUsage(), result.billableUsage(),
                assignment.defaultQuantity(), price.unit(), displayUnitPrice(price), amounts.subtotalMinor(),
                amounts.discountMinor(), amounts.taxMinor(), amounts.totalMinor(), calculation.toString(),
                display.toString());
    }

    private AllocatedAmounts allocate(BillingResult result, AssignmentMode mode, BigDecimal allocationValue,
                                      int minorUnit, UUID contractItemId) {
        if (mode == AssignmentMode.DISPLAY_ONLY) {
            return new AllocatedAmounts(0, 0, 0, 0);
        }
        if (mode == AssignmentMode.CHARGE) {
            return new AllocatedAmounts(result.subtotalMinor(), result.discountMinor(),
                    result.taxMinor(), result.totalMinor());
        }
        if (mode == AssignmentMode.ALLOCATE_PERCENT) {
            BigDecimal factor = allocationValue.divide(new BigDecimal("100"), 18, RoundingMode.HALF_UP);
            long subtotal = scaleMinor(result.subtotalMinor(), factor);
            long discount = scaleMinor(result.discountMinor(), factor);
            long tax = scaleMinor(result.taxMinor(), factor);
            return new AllocatedAmounts(subtotal, discount, tax, Math.addExact(Math.subtractExact(subtotal, discount), tax));
        }
        long fixedTotal = allocationValue.movePointRight(minorUnit).setScale(0, RoundingMode.HALF_UP).longValueExact();
        if (fixedTotal > result.totalMinor()) {
            throw new DomainException("FIXED_ALLOCATION_EXCEEDS_CHARGE",
                    "Fixed allocation cannot exceed the calculated source charge", 422,
                    Map.of("contract_item_id", contractItemId, "allocation_minor", fixedTotal,
                            "source_total_minor", result.totalMinor()));
        }
        if (fixedTotal == 0) {
            return new AllocatedAmounts(0, 0, 0, 0);
        }
        if (result.totalMinor() <= 0) {
            throw new DomainException("FIXED_ALLOCATION_INVALID",
                    "A positive fixed allocation requires a positive calculated source charge", 422,
                    Map.of("contract_item_id", contractItemId));
        }
        BigDecimal factor = BigDecimal.valueOf(fixedTotal)
                .divide(BigDecimal.valueOf(result.totalMinor()), 18, RoundingMode.HALF_UP);
        long subtotal = scaleMinor(result.subtotalMinor(), factor);
        long discount = scaleMinor(result.discountMinor(), factor);
        long tax = Math.addExact(Math.subtractExact(fixedTotal, subtotal), discount);
        return new AllocatedAmounts(subtotal, discount, tax, fixedTotal);
    }

    private Totals totals(List<GeneratedLine> lines, Set<String> exclusions, List<Adjustment> adjustments) {
        long subtotal = 0;
        long discount = 0;
        long tax = 0;
        for (GeneratedLine line : lines) {
            if (!exclusions.contains(line.sourceKey())) {
                subtotal = Math.addExact(subtotal, line.subtotalMinor());
                discount = Math.addExact(discount, line.discountMinor());
                tax = Math.addExact(tax, line.taxMinor());
            }
        }
        long adjustment = 0;
        for (Adjustment value : adjustments) {
            adjustment = Math.addExact(adjustment, value.amountMinor());
            if (value.includedInTaxBase() && value.taxRate() != null) {
                long adjustmentTax = BigDecimal.valueOf(value.amountMinor()).multiply(value.taxRate())
                        .setScale(0, RoundingMode.HALF_UP).longValueExact();
                tax = Math.addExact(tax, adjustmentTax);
            }
        }
        long total = Math.addExact(Math.addExact(Math.subtractExact(subtotal, discount), tax), adjustment);
        if (total < 0) {
            throw new DomainException("NEGATIVE_INVOICE_TOTAL", "Preview total cannot be negative", 422,
                    Map.of("total_minor", total));
        }
        return new Totals(subtotal, discount, tax, adjustment, total);
    }

    private ObjectNode renderModel(String previewNumber, Profile profile, OffsetDateTime periodStart,
                                   OffsetDateTime periodEnd, LocalDate issueDate, LocalDate dueDate,
                                   List<GeneratedLine> lines, Set<String> exclusions,
                                   List<Adjustment> adjustments, Totals totals) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schema_version", 1);
        root.put("preview_number", previewNumber);
        root.put("period_start", periodStart.toString());
        root.put("period_end", periodEnd.toString());
        root.put("issue_date", issueDate.toString());
        root.put("due_date", dueDate.toString());
        root.put("currency_code", profile.currency());
        root.set("profile", readJson(profile.profileSnapshot()));
        root.set("party", readJson(profile.partySnapshot()));
        ArrayNode itemNodes = root.putArray("items");
        for (GeneratedLine line : lines) {
            ObjectNode item = objectMapper.valueToTree(line);
            item.put("excluded", exclusions.contains(line.sourceKey()));
            itemNodes.add(item);
        }
        ArrayNode adjustmentNodes = root.putArray("adjustments");
        adjustments.forEach(value -> adjustmentNodes.add(objectMapper.valueToTree(value)));
        root.set("totals", objectMapper.valueToTree(totals));
        return root;
    }

    private void insertLines(UUID tenantId, UUID previewId, List<GeneratedLine> lines) {
        for (GeneratedLine line : lines) {
            jdbc.sql("""
                            INSERT INTO invoice_preview_items(
                                id, tenant_id, invoice_preview_id, contract_item_id, service_id,
                                pricing_rule_version_id, usage_snapshot_id, source_key, line_no, item_name,
                                billing_period_start, billing_period_end, raw_usage, converted_usage,
                                rounded_usage, billing_usage, quantity, unit, unit_price, subtotal_minor,
                                discount_minor, tax_minor, total_minor, calculation_snapshot_json, display_json
                            ) VALUES (
                                :id, :tenantId, :previewId, :contractItemId, :serviceId,
                                :pricingVersionId, :usageSnapshotId, :sourceKey, :lineNo, :itemName,
                                :periodStart, :periodEnd, :rawUsage, :convertedUsage,
                                :roundedUsage, :billingUsage, :quantity, :unit, :unitPrice, :subtotal,
                                :discount, :tax, :total, CAST(:calculation AS jsonb), CAST(:display AS jsonb)
                            )
                            """)
                    .param("id", UuidV7.generate()).param("tenantId", tenantId).param("previewId", previewId)
                    .param("contractItemId", line.contractItemId()).param("serviceId", line.serviceId())
                    .param("pricingVersionId", line.pricingVersionId()).param("usageSnapshotId", line.usageSnapshotId())
                    .param("sourceKey", line.sourceKey()).param("lineNo", line.lineNo()).param("itemName", line.itemName())
                    .param("periodStart", line.periodStart()).param("periodEnd", line.periodEnd())
                    .param("rawUsage", line.rawUsage()).param("convertedUsage", line.convertedUsage())
                    .param("roundedUsage", line.roundedUsage()).param("billingUsage", line.billingUsage())
                    .param("quantity", line.quantity()).param("unit", line.unit()).param("unitPrice", line.unitPrice())
                    .param("subtotal", line.subtotalMinor()).param("discount", line.discountMinor())
                    .param("tax", line.taxMinor()).param("total", line.totalMinor())
                    .param("calculation", line.calculationSnapshot()).param("display", line.display()).update();
        }
    }

    private void reapplyExclusions(UUID tenantId, UUID previewId, Set<String> sourceKeys, UUID actorId) {
        for (String sourceKey : sourceKeys) {
            UUID itemId = jdbc.sql("""
                            SELECT id FROM invoice_preview_items
                            WHERE tenant_id = :tenantId AND invoice_preview_id = :previewId AND source_key = :sourceKey
                            """)
                    .param("tenantId", tenantId).param("previewId", previewId).param("sourceKey", sourceKey)
                    .query(UUID.class).single();
            jdbc.sql("""
                            INSERT INTO invoice_preview_exclusions(
                                id, tenant_id, invoice_preview_id, invoice_preview_item_id, reason, created_by
                            ) VALUES (:id, :tenantId, :previewId, :itemId, :reason, :actorId)
                            """)
                    .param("id", UuidV7.generate()).param("tenantId", tenantId).param("previewId", previewId)
                    .param("itemId", itemId).param("reason", "Preserved during recalculation")
                    .param("actorId", actorId).update();
        }
    }

    private Profile loadProfile(UUID tenantId, UUID profileId) {
        return jdbc.sql("""
                        SELECT profile.id, profile.profile_code, profile.customer_id, profile.company_id,
                               profile.template_id, template.current_version_id AS template_version_id,
                               profile.approval_workflow_id,
                               workflow.current_version_id AS workflow_version_id,
                               profile.language, profile.currency_code, profile.timezone,
                               profile.payment_terms_days, profile.auto_submit_review, profile.status,
                               template.status AS template_status, template_version.status AS template_version_status,
                               workflow.status AS workflow_status, workflow_version.status AS workflow_version_status,
                               currency.minor_unit,
                               jsonb_build_object(
                                   'id', profile.id, 'profile_code', profile.profile_code,
                                   'profile_name', profile.profile_name, 'language', profile.language,
                                   'currency_code', profile.currency_code, 'timezone', profile.timezone,
                                   'payment_terms_days', profile.payment_terms_days,
                                   'auto_send', profile.auto_send,
                                   'tax_calculation_mode', profile.tax_calculation_mode,
                                   'invoice_number_rule', profile.invoice_number_rule,
                                   'payment_account', profile.payment_account_json,
                                   'recipients', profile.recipients_json
                               ) AS profile_snapshot,
                               jsonb_build_object(
                                   'customer_id', customer.id, 'customer_no', customer.customer_no,
                                   'customer_name', customer.customer_name,
                                   'company_id', company.id, 'company_code', company.company_code,
                                   'company_name', company.company_name, 'company_name_en', company.company_name_en,
                                   'address', company.address, 'tax_number', company.tax_number,
                                   'invoice_title', company.invoice_title,
                                   'invoice_profile', company.invoice_profile_json
                               ) AS party_snapshot
                        FROM invoice_profiles profile
                        JOIN customers customer ON customer.tenant_id = profile.tenant_id AND customer.id = profile.customer_id
                        JOIN companies company ON company.tenant_id = profile.tenant_id AND company.id = profile.company_id
                        JOIN currencies currency ON currency.code = profile.currency_code AND currency.enabled
                        LEFT JOIN invoice_templates template ON template.tenant_id = profile.tenant_id
                             AND template.id = profile.template_id
                        LEFT JOIN invoice_template_versions template_version ON template_version.tenant_id = profile.tenant_id
                             AND template_version.id = template.current_version_id
                        LEFT JOIN approval_workflows workflow ON workflow.tenant_id = profile.tenant_id
                             AND workflow.id = profile.approval_workflow_id
                        LEFT JOIN approval_workflow_versions workflow_version ON workflow_version.tenant_id = profile.tenant_id
                             AND workflow_version.id = workflow.current_version_id
                        WHERE profile.tenant_id = :tenantId AND profile.id = :profileId
                        FOR UPDATE OF profile
                        """)
                .param("tenantId", tenantId).param("profileId", profileId).query(this::mapProfile).optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Invoice profile was not found", 404,
                        Map.of("invoice_profile_id", profileId)));
    }

    private Profile mapProfile(ResultSet rs, int row) throws SQLException {
        if (!"ACTIVE".equals(rs.getString("status"))) {
            throw new DomainException("INVOICE_PROFILE_INACTIVE", "Only active invoice profiles can generate previews", 409,
                    Map.of("status", rs.getString("status")));
        }
        UUID templateId = rs.getObject("template_id", UUID.class);
        UUID templateVersionId = rs.getObject("template_version_id", UUID.class);
        UUID workflowVersionId = rs.getObject("workflow_version_id", UUID.class);
        if (templateId == null || templateVersionId == null || !"ACTIVE".equals(rs.getString("template_status"))
                || !"PUBLISHED".equals(rs.getString("template_version_status"))) {
            throw new DomainException("PUBLISHED_TEMPLATE_REQUIRED", "An active published invoice template is required", 422,
                    Map.of());
        }
        if (workflowVersionId == null || !"ACTIVE".equals(rs.getString("workflow_status"))
                || !"PUBLISHED".equals(rs.getString("workflow_version_status"))) {
            throw new DomainException("APPROVAL_WORKFLOW_REQUIRED", "An active published approval workflow is required", 422,
                    Map.of());
        }
        return new Profile(rs.getObject("id", UUID.class), rs.getString("profile_code"),
                rs.getObject("customer_id", UUID.class), rs.getObject("company_id", UUID.class), templateId,
                templateVersionId, workflowVersionId, rs.getString("language"), rs.getString("currency_code"),
                rs.getString("timezone"), rs.getInt("payment_terms_days"), rs.getBoolean("auto_submit_review"),
                rs.getInt("minor_unit"), rs.getString("profile_snapshot"), rs.getString("party_snapshot"));
    }

    private List<Assignment> loadAssignments(UUID tenantId, UUID profileId, OffsetDateTime periodStart,
                                             OffsetDateTime periodEnd) {
        return jdbc.sql("""
                        SELECT assignment.id, assignment.contract_item_id, assignment.assignment_mode,
                               assignment.allocation_value, assignment.effective_from AS assignment_from,
                               assignment.effective_to AS assignment_to, contract_item.service_id,
                               contract_item.pricing_rule_id, contract_item.item_name,
                               contract_item.billing_type, contract_item.default_quantity, contract_item.unit,
                               contract_item.effective_from AS item_from, contract_item.effective_to AS item_to,
                               contract.effective_from AS contract_from, contract.effective_to AS contract_to,
                               contract.tax_rate, contract.tax_inclusive,
                               service.activated_on AS service_from, service.deactivated_on AS service_to
                        FROM invoice_profile_assignments assignment
                        JOIN contract_items contract_item ON contract_item.tenant_id = assignment.tenant_id
                             AND contract_item.id = assignment.contract_item_id
                        JOIN contracts contract ON contract.tenant_id = assignment.tenant_id
                             AND contract.id = contract_item.contract_id
                        JOIN services service ON service.tenant_id = assignment.tenant_id
                             AND service.id = contract_item.service_id
                        WHERE assignment.tenant_id = :tenantId AND assignment.invoice_profile_id = :profileId
                          AND assignment.status = 'ACTIVE' AND assignment.effective_from < :periodEnd
                          AND (assignment.effective_to IS NULL OR assignment.effective_to > :periodStart)
                          AND contract_item.status = 'ACTIVE' AND contract_item.auto_bill
                          AND contract_item.effective_from < :periodEnd
                          AND (contract_item.effective_to IS NULL OR contract_item.effective_to > :periodStart)
                          AND contract.status = 'ACTIVE' AND service.status = 'ACTIVE'
                        ORDER BY assignment.sort_order, contract_item.sort_order, contract_item.contract_item_no
                        """)
                .param("tenantId", tenantId).param("profileId", profileId).param("periodStart", periodStart)
                .param("periodEnd", periodEnd).query(this::mapAssignment).list();
    }

    private Assignment mapAssignment(ResultSet rs, int row) throws SQLException {
        return new Assignment(rs.getObject("id", UUID.class), rs.getObject("contract_item_id", UUID.class),
                rs.getObject("service_id", UUID.class), rs.getObject("pricing_rule_id", UUID.class),
                rs.getString("item_name"), rs.getString("billing_type"), rs.getBigDecimal("default_quantity"),
                rs.getString("unit"), AssignmentMode.valueOf(rs.getString("assignment_mode")),
                rs.getBigDecimal("allocation_value"), rs.getObject("assignment_from", OffsetDateTime.class),
                rs.getObject("assignment_to", OffsetDateTime.class), rs.getObject("item_from", OffsetDateTime.class),
                rs.getObject("item_to", OffsetDateTime.class), rs.getObject("contract_from", LocalDate.class),
                rs.getObject("contract_to", LocalDate.class), rs.getObject("service_from", LocalDate.class),
                rs.getObject("service_to", LocalDate.class), rs.getBigDecimal("tax_rate"),
                rs.getBoolean("tax_inclusive"));
    }

    private List<PriceVersion> loadPriceVersions(UUID tenantId, UUID pricingRuleId,
                                                 OffsetDateTime start, OffsetDateTime end) {
        List<PriceVersionBase> bases = jdbc.sql("""
                        SELECT * FROM pricing_rule_versions
                        WHERE tenant_id = :tenantId AND pricing_rule_id = :ruleId AND status = 'PUBLISHED'
                          AND effective_from < :end AND (effective_to IS NULL OR effective_to > :start)
                        ORDER BY effective_from, version_no
                        """)
                .param("tenantId", tenantId).param("ruleId", pricingRuleId).param("start", start).param("end", end)
                .query(this::mapPriceBase).list();
        List<PriceVersion> result = new ArrayList<>();
        for (PriceVersionBase base : bases) {
            List<PricingTier> tiers = jdbc.sql("""
                            SELECT lower_bound, upper_bound, unit_price FROM pricing_tiers
                            WHERE tenant_id = :tenantId AND pricing_rule_version_id = :versionId ORDER BY tier_no
                            """)
                    .param("tenantId", tenantId).param("versionId", base.id())
                    .query((rs, row) -> new PricingTier(rs.getBigDecimal("lower_bound"),
                            rs.getBigDecimal("upper_bound"), rs.getBigDecimal("unit_price"))).list();
            result.add(base.withTiers(tiers));
        }
        return result;
    }

    private PriceVersionBase mapPriceBase(ResultSet rs, int row) throws SQLException {
        return new PriceVersionBase(rs.getObject("id", UUID.class), rs.getObject("effective_from", OffsetDateTime.class),
                rs.getObject("effective_to", OffsetDateTime.class), rs.getString("billing_type"),
                rs.getString("currency_code"), rs.getString("unit"), rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("base_fee"), rs.getBigDecimal("committed_quantity"),
                rs.getBigDecimal("overage_unit_price"), rs.getBigDecimal("minimum_charge"),
                rs.getBigDecimal("maximum_charge"), rs.getBigDecimal("discount_rate"),
                rs.getBigDecimal("tax_rate"), rs.getString("rounding_mode"),
                rs.getObject("rounding_scale", Integer.class), readJson(rs.getString("config_json")));
    }

    private Usage loadUsage(UUID tenantId, UUID contractItemId, OffsetDateTime start, OffsetDateTime end) {
        return jdbc.sql("""
                        SELECT id, raw_usage, converted_usage, rounded_usage, billing_usage, data_hash
                        FROM usage_snapshots
                        WHERE tenant_id = :tenantId AND contract_item_id = :contractItemId
                          AND period_start = :start AND period_end = :end AND invalidated_at IS NULL
                          AND snapshot_kind IN ('PREVIEW', 'FINAL')
                        ORDER BY CASE snapshot_kind WHEN 'FINAL' THEN 0 ELSE 1 END, created_at DESC
                        LIMIT 1
                        """)
                .param("tenantId", tenantId).param("contractItemId", contractItemId)
                .param("start", start).param("end", end).query((rs, row) -> new Usage(
                        rs.getObject("id", UUID.class), rs.getBigDecimal("raw_usage"),
                        rs.getBigDecimal("converted_usage"), rs.getBigDecimal("rounded_usage"),
                        rs.getBigDecimal("billing_usage"), rs.getString("data_hash"))).optional()
                .orElseThrow(() -> new DomainException("USAGE_SNAPSHOT_MISSING",
                        "An exact immutable usage snapshot is required for usage-based billing", 422,
                        Map.of("contract_item_id", contractItemId, "period_start", start, "period_end", end)));
    }

    private List<Adjustment> loadAdjustments(UUID tenantId, UUID previewId) {
        return jdbc.sql("""
                        SELECT id, adjustment_type, description, amount_minor, tax_rate,
                               included_in_tax_base, reason, attachment_file_id, created_by
                        FROM invoice_preview_adjustments
                        WHERE tenant_id = :tenantId AND invoice_preview_id = :previewId AND status = 'ACTIVE'
                        ORDER BY created_at, id
                        """)
                .param("tenantId", tenantId).param("previewId", previewId).query((rs, row) -> new Adjustment(
                        rs.getObject("id", UUID.class), rs.getString("adjustment_type"), rs.getString("description"),
                        rs.getLong("amount_minor"), rs.getBigDecimal("tax_rate"), rs.getBoolean("included_in_tax_base"),
                        rs.getString("reason"), rs.getObject("attachment_file_id", UUID.class),
                        rs.getObject("created_by", UUID.class))).list();
    }

    private EditablePreview lockEditablePreview(UUID tenantId, UUID previewId, long expectedVersion) {
        EditablePreview preview = jdbc.sql("""
                        SELECT id, preview_number, invoice_profile_id, period_start, period_end,
                               issue_date, due_date, status, version
                        FROM invoice_previews
                        WHERE tenant_id = :tenantId AND id = :previewId FOR UPDATE
                        """)
                .param("tenantId", tenantId).param("previewId", previewId).query((rs, row) -> new EditablePreview(
                        rs.getObject("id", UUID.class), rs.getString("preview_number"),
                        rs.getObject("invoice_profile_id", UUID.class), rs.getObject("period_start", OffsetDateTime.class),
                        rs.getObject("period_end", OffsetDateTime.class), rs.getObject("issue_date", LocalDate.class),
                        rs.getObject("due_date", LocalDate.class), rs.getString("status"), rs.getLong("version")))
                .optional().orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Invoice preview was not found", 404,
                        Map.of("preview_id", previewId)));
        if (preview.version() != expectedVersion) {
            throw versionConflict(expectedVersion);
        }
        if (Set.of("FINALIZING", "FINALIZED", "VOIDED").contains(preview.status())) {
            throw new DomainException("PREVIEW_NOT_EDITABLE", "The preview can no longer be recalculated", 409,
                    Map.of("status", preview.status()));
        }
        return preview;
    }

    private void invalidateApproval(UUID tenantId, UUID previewId, UUID actorId, long previewVersion, String reason) {
        List<UUID> pending = jdbc.sql("""
                        SELECT id FROM approval_instances
                        WHERE tenant_id = :tenantId AND invoice_preview_id = :previewId AND status = 'PENDING'
                        FOR UPDATE
                        """)
                .param("tenantId", tenantId).param("previewId", previewId).query(UUID.class).list();
        for (UUID approvalId : pending) {
            jdbc.sql("""
                            UPDATE approval_instances SET status = 'INVALIDATED', completed_at = now(),
                                invalidation_reason = :reason
                            WHERE tenant_id = :tenantId AND id = :approvalId
                            """)
                    .param("reason", reason).param("tenantId", tenantId)
                    .param("approvalId", approvalId).update();
            jdbc.sql("""
                            INSERT INTO approval_actions(
                                id, tenant_id, approval_instance_id, preview_version, action, actor_id, comment
                            ) VALUES (:id, :tenantId, :approvalId, :previewVersion, 'INVALIDATE', :actorId, :reason)
                            """)
                    .param("id", UuidV7.generate()).param("tenantId", tenantId).param("approvalId", approvalId)
                    .param("previewVersion", previewVersion).param("actorId", actorId).param("reason", reason).update();
        }
    }

    private ExistingPreview findByNumber(UUID tenantId, String previewNumber) {
        return jdbc.sql("""
                        SELECT id, preview_number, status, version FROM invoice_previews
                        WHERE tenant_id = :tenantId AND preview_number = :previewNumber
                        """)
                .param("tenantId", tenantId).param("previewNumber", previewNumber)
                .query((rs, row) -> new ExistingPreview(rs.getObject("id", UUID.class),
                        rs.getString("preview_number"), rs.getString("status"), rs.getLong("version")))
                .optional().orElse(null);
    }

    private String previewNumber(Profile profile, OffsetDateTime periodStart, UUID generationId) {
        String suffix = generationId.toString().replace("-", "").substring(0, 8).toUpperCase();
        String profileCode = profile.code().replaceAll("[^A-Za-z0-9_-]", "-").toUpperCase();
        return "PRE-" + periodStart.format(PERIOD_KEY) + "-" + profileCode + "-" + suffix;
    }

    private DomainException pricingGap(Assignment assignment, OffsetDateTime start, OffsetDateTime end) {
        return new DomainException("PRICING_VERSION_MISSING",
                "A published pricing version must cover every chargeable instant", 422,
                Map.of("contract_item_id", assignment.contractItemId(), "gap_start", start, "gap_end", end));
    }

    private void requirePeriod(OffsetDateTime start, OffsetDateTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new DomainException("INVALID_BILLING_PERIOD", "Billing period must be a non-empty half-open interval", 422,
                    Map.of());
        }
    }

    private boolean requiresUsage(BillingType type) {
        return type != BillingType.FIXED_FEE && type != BillingType.QUANTITY;
    }

    private BigDecimal displayUnitPrice(PriceVersion price) {
        return price.unitPrice() == null ? price.overageUnitPrice() : price.unitPrice();
    }

    private long scaleMinor(long source, BigDecimal factor) {
        return BigDecimal.valueOf(source).multiply(factor).setScale(0, RoundingMode.HALF_UP).longValueExact();
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

    private String text(JsonNode node, String field, String defaultValue) {
        return node == null || !node.hasNonNull(field) || node.path(field).asText().isBlank()
                ? defaultValue : node.path(field).asText();
    }

    private <T extends Enum<T>> T enumValue(Class<T> type, String value, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new DomainException("PRICING_VERSION_INVALID", field + " is invalid", 422,
                    Map.of("field", field, "value", value));
        }
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value == null ? "{}" : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted JSON is invalid", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private OffsetDateTime atStart(LocalDate value, ZoneId zone) {
        return value == null ? null : value.atStartOfDay(zone).toOffsetDateTime();
    }

    private OffsetDateTime afterServiceEnd(LocalDate value, ZoneId zone) {
        return value == null ? null : value.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
    }

    private OffsetDateTime maximum(OffsetDateTime first, OffsetDateTime... values) {
        OffsetDateTime result = first;
        for (OffsetDateTime value : values) {
            if (value != null && value.isAfter(result)) {
                result = value;
            }
        }
        return result;
    }

    private OffsetDateTime minimum(OffsetDateTime first, OffsetDateTime... values) {
        OffsetDateTime result = first;
        for (OffsetDateTime value : values) {
            if (value != null && value.isBefore(result)) {
                result = value;
            }
        }
        return result;
    }

    private DomainException versionConflict(long expectedVersion) {
        return new DomainException("VERSION_CONFLICT", "Invoice preview was modified by another request", 409,
                Map.of("expected_version", expectedVersion));
    }

    public record GenerationResult(UUID previewId, String previewNumber, String status,
                                   long version, boolean autoSubmitReview) {
    }

    private record Profile(UUID id, String code, UUID customerId, UUID companyId, UUID templateId,
                           UUID templateVersionId, UUID workflowVersionId, String language, String currency,
                           String timezone, int paymentTermsDays, boolean autoSubmitReview, int minorUnit,
                           String profileSnapshot, String partySnapshot) {
    }

    private record Assignment(UUID id, UUID contractItemId, UUID serviceId, UUID pricingRuleId,
                              String itemName, String billingType, BigDecimal defaultQuantity, String unit,
                              AssignmentMode mode, BigDecimal allocationValue, OffsetDateTime assignmentFrom,
                              OffsetDateTime assignmentTo, OffsetDateTime itemFrom, OffsetDateTime itemTo,
                              LocalDate contractFrom, LocalDate contractTo, LocalDate serviceFrom,
                              LocalDate serviceTo, BigDecimal taxRate, boolean taxInclusive) {
    }

    private record PriceVersionBase(UUID id, OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo,
                                    String billingType, String currency, String unit, BigDecimal unitPrice,
                                    BigDecimal baseFee, BigDecimal committedQuantity, BigDecimal overageUnitPrice,
                                    BigDecimal minimumCharge, BigDecimal maximumCharge, BigDecimal discountRate,
                                    BigDecimal taxRate, String roundingMode, Integer roundingScale, JsonNode config) {
        PriceVersion withTiers(List<PricingTier> tiers) {
            return new PriceVersion(id, effectiveFrom, effectiveTo, billingType, currency, unit, unitPrice,
                    baseFee, committedQuantity, overageUnitPrice, minimumCharge, maximumCharge,
                    discountRate, taxRate, roundingMode, roundingScale, config, List.copyOf(tiers));
        }
    }

    private record PriceVersion(UUID id, OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo,
                                String billingType, String currency, String unit, BigDecimal unitPrice,
                                BigDecimal baseFee, BigDecimal committedQuantity, BigDecimal overageUnitPrice,
                                BigDecimal minimumCharge, BigDecimal maximumCharge, BigDecimal discountRate,
                                BigDecimal taxRate, String roundingMode, Integer roundingScale, JsonNode config,
                                List<PricingTier> tiers) {
    }

    private record Usage(UUID id, BigDecimal rawUsage, BigDecimal convertedUsage,
                         BigDecimal roundedUsage, BigDecimal billingUsage, String dataHash) {
    }

    private record GeneratedLine(UUID contractItemId, UUID serviceId, UUID pricingVersionId,
                                 UUID usageSnapshotId, String sourceKey, int lineNo, String itemName,
                                 OffsetDateTime periodStart, OffsetDateTime periodEnd, BigDecimal rawUsage,
                                 BigDecimal convertedUsage, BigDecimal roundedUsage, BigDecimal billingUsage,
                                 BigDecimal quantity, String unit, BigDecimal unitPrice, long subtotalMinor,
                                 long discountMinor, long taxMinor, long totalMinor,
                                 String calculationSnapshot, String display) {
    }

    private record AllocatedAmounts(long subtotalMinor, long discountMinor, long taxMinor, long totalMinor) {
    }

    private record Adjustment(UUID id, String type, String description, long amountMinor, BigDecimal taxRate,
                              boolean includedInTaxBase, String reason, UUID attachmentFileId, UUID createdBy) {
    }

    private record Totals(long subtotalMinor, long discountMinor, long taxMinor,
                          long adjustmentMinor, long totalMinor) {
    }

    private record ExistingPreview(UUID id, String previewNumber, String status, long version) {
    }

    private record EditablePreview(UUID id, String previewNumber, UUID profileId,
                                   OffsetDateTime periodStart, OffsetDateTime periodEnd,
                                   LocalDate issueDate, LocalDate dueDate, String status, long version) {
    }
}
