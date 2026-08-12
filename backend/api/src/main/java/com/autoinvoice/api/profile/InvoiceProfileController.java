package com.autoinvoice.api.profile;

import com.autoinvoice.api.http.VersionEtag;
import com.autoinvoice.api.idempotency.IdempotencyExecutor;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.audit.AuditService;
import com.autoinvoice.platform.jobs.BackgroundJobService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class InvoiceProfileController {
    private static final String DEFAULT_WORKFLOW = "DEFAULT_TWO_LEVEL";
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final IdempotencyExecutor idempotency;
    private final BackgroundJobService jobs;
    private final AuditService audit;

    public InvoiceProfileController(JdbcClient jdbc, ObjectMapper objectMapper, IdempotencyExecutor idempotency,
                                    BackgroundJobService jobs, AuditService audit) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.idempotency = idempotency;
        this.jobs = jobs;
        this.audit = audit;
    }

    @GetMapping("/invoice-profiles")
    @PreAuthorize("hasAnyAuthority('preview.generate','contract.write','invoice.finalize')")
    public List<ProfileResponse> list(Authentication authentication,
                                      @RequestParam(name = "customer_id", required = false) UUID customerId,
                                      @RequestParam(required = false) String status) {
        return jdbc.sql("""
                        SELECT * FROM invoice_profiles WHERE tenant_id = :tenantId
                          AND (CAST(:customerId AS uuid) IS NULL OR customer_id = :customerId)
                          AND (CAST(:status AS varchar) IS NULL OR status = :status)
                        ORDER BY profile_code
                        """)
                .param("tenantId", user(authentication).tenantId()).param("customerId", customerId)
                .param("status", blank(status)).query(this::mapProfile).list();
    }

    @GetMapping("/invoice-profiles/{id}")
    @PreAuthorize("hasAnyAuthority('preview.generate','contract.write','invoice.finalize')")
    public ResponseEntity<ProfileDetail> get(Authentication authentication, @PathVariable UUID id) {
        UUID tenantId = user(authentication).tenantId();
        ProfileResponse profile = findProfile(tenantId, id);
        return ResponseEntity.ok().eTag(VersionEtag.format(profile.version()))
                .body(new ProfileDetail(profile, assignments(tenantId, id), validateProfile(tenantId, id)));
    }

    @PostMapping("/invoice-profiles")
    @PreAuthorize("hasAuthority('contract.write')")
    public ResponseEntity<ProfileResponse> create(Authentication authentication,
                                                  @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                  @Valid @RequestBody ProfileCreateRequest request,
                                                  HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST",
                "/api/v1/invoice-profiles", request,
                ProfileResponse.class, () -> create(actor, request, servletRequest));
    }

    @Transactional
    protected ResponseEntity<ProfileResponse> create(AuthenticatedUser actor, ProfileCreateRequest request,
                                                     HttpServletRequest servletRequest) {
        requireCompany(actor.tenantId(), request.customerId(), request.companyId());
        requirePublishedTemplate(actor.tenantId(), request.templateId());
        UUID workflowId = request.approvalWorkflowId() == null
                ? ensureDefaultWorkflow(actor.tenantId()) : requireWorkflow(actor.tenantId(), request.approvalWorkflowId());
        UUID id = UuidV7.generate();
        jdbc.sql("""
                        INSERT INTO invoice_profiles(
                            id, tenant_id, profile_code, profile_name, customer_id, company_id,
                            template_id, approval_workflow_id, language, currency_code, timezone,
                            billing_cycle, billing_day, payment_terms_days, tax_calculation_mode,
                            invoice_number_rule, payment_account_json, recipients_json,
                            auto_generate, auto_submit_review, auto_send, notes
                        ) VALUES (
                            :id, :tenantId, :code, :name, :customerId, :companyId,
                            :templateId, :workflowId, :language, :currency, :timezone,
                            :billingCycle, :billingDay, :paymentTerms, :taxMode,
                            :numberRule, CAST(:paymentAccount AS jsonb), CAST(:recipients AS jsonb),
                            :autoGenerate, :autoSubmit, :autoSend, :notes
                        )
                        """)
                .param("id", id).param("tenantId", actor.tenantId()).param("code", request.profileCode())
                .param("name", request.profileName()).param("customerId", request.customerId())
                .param("companyId", request.companyId()).param("templateId", request.templateId())
                .param("workflowId", workflowId).param("language", request.language())
                .param("currency", request.currencyCode()).param("timezone", request.timezone())
                .param("billingCycle", request.billingCycle()).param("billingDay", request.billingDay())
                .param("paymentTerms", request.paymentTermsDays()).param("taxMode", request.taxCalculationMode())
                .param("numberRule", request.invoiceNumberRule()).param("paymentAccount", jsonObject(request.paymentAccount()))
                .param("recipients", jsonArray(request.recipients())).param("autoGenerate", request.autoGenerate())
                .param("autoSubmit", request.autoSubmitReview()).param("autoSend", request.autoSend())
                .param("notes", request.notes()).update();
        ProfileResponse created = findProfile(actor.tenantId(), id);
        record(actor, "invoice_profile.created", "invoice_profile", id, null, created,
                request.reason(), servletRequest);
        return ResponseEntity.status(HttpStatus.CREATED).eTag(VersionEtag.format(0)).body(created);
    }

    @PatchMapping("/invoice-profiles/{id}")
    @PreAuthorize("hasAuthority('contract.write')")
    @Transactional
    public ResponseEntity<ProfileResponse> update(Authentication authentication, @PathVariable UUID id,
                                                  @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                  @Valid @RequestBody ProfileUpdateRequest request,
                                                  HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        ProfileResponse before = findProfile(actor.tenantId(), id);
        if (request.templateId() != null) {
            requirePublishedTemplate(actor.tenantId(), request.templateId());
        }
        if ("ACTIVE".equals(request.status())) {
            ValidationResponse validation = validateProfile(actor.tenantId(), id);
            if (!validation.valid()) {
                throw new DomainException("INVOICE_PROFILE_INVALID", "Invoice profile must pass validation before activation", 422,
                        Map.of("errors", validation.errors()));
            }
        }
        long version = VersionEtag.parse(ifMatch);
        int changed = jdbc.sql("""
                        UPDATE invoice_profiles SET profile_name = COALESCE(:name, profile_name),
                            template_id = COALESCE(:templateId, template_id), language = COALESCE(:language, language),
                            timezone = COALESCE(:timezone, timezone), billing_day = COALESCE(:billingDay, billing_day),
                            payment_terms_days = COALESCE(:paymentTerms, payment_terms_days),
                            invoice_number_rule = COALESCE(:numberRule, invoice_number_rule),
                            payment_account_json = COALESCE(CAST(:paymentAccount AS jsonb), payment_account_json),
                            recipients_json = COALESCE(CAST(:recipients AS jsonb), recipients_json),
                            auto_generate = COALESCE(:autoGenerate, auto_generate),
                            auto_submit_review = COALESCE(:autoSubmit, auto_submit_review),
                            auto_send = COALESCE(:autoSend, auto_send), status = COALESCE(:status, status),
                            notes = COALESCE(:notes, notes), updated_at = now(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :id AND version = :version
                        """)
                .param("name", request.profileName()).param("templateId", request.templateId())
                .param("language", request.language()).param("timezone", request.timezone())
                .param("billingDay", request.billingDay()).param("paymentTerms", request.paymentTermsDays())
                .param("numberRule", request.invoiceNumberRule()).param("paymentAccount", nullableJson(request.paymentAccount()))
                .param("recipients", nullableJson(request.recipients())).param("autoGenerate", request.autoGenerate())
                .param("autoSubmit", request.autoSubmitReview()).param("autoSend", request.autoSend())
                .param("status", request.status()).param("notes", request.notes()).param("tenantId", actor.tenantId())
                .param("id", id).param("version", version).update();
        if (changed != 1) {
            throw new DomainException("VERSION_CONFLICT", "Invoice profile was modified by another request", 409,
                    Map.of("expected_version", version));
        }
        ProfileResponse after = findProfile(actor.tenantId(), id);
        record(actor, "invoice_profile.updated", "invoice_profile", id, before, after,
                request.reason(), servletRequest);
        return ResponseEntity.ok().eTag(VersionEtag.format(after.version())).body(after);
    }

    @PostMapping("/invoice-profiles/{profileId}/assignments")
    @PreAuthorize("hasAuthority('contract.write')")
    public ResponseEntity<AssignmentResponse> addAssignment(Authentication authentication, @PathVariable UUID profileId,
                                                            @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                            @Valid @RequestBody AssignmentCreateRequest request,
                                                            HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        String path = "/api/v1/invoice-profiles/" + profileId + "/assignments";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                AssignmentResponse.class, () -> {
                    ProfileResponse profile = findProfile(actor.tenantId(), profileId);
                    validateItemBelongs(actor.tenantId(), profile, request.contractItemId());
                    UUID id = UuidV7.generate();
                    jdbc.sql("""
                                    INSERT INTO invoice_profile_assignments(
                                        id, tenant_id, invoice_profile_id, contract_item_id, assignment_mode,
                                        allocation_value, effective_from, effective_to, sort_order, reason, created_by
                                    ) VALUES (
                                        :id, :tenantId, :profileId, :itemId, :mode,
                                        :value, :effectiveFrom, :effectiveTo, :sortOrder, :reason, :actorId
                                    )
                                    """)
                            .param("id", id).param("tenantId", actor.tenantId()).param("profileId", profileId)
                            .param("itemId", request.contractItemId()).param("mode", request.assignmentMode())
                            .param("value", request.allocationValue()).param("effectiveFrom", request.effectiveFrom())
                            .param("effectiveTo", request.effectiveTo()).param("sortOrder", request.sortOrder())
                            .param("reason", request.reason()).param("actorId", actor.userId()).update();
                    AssignmentResponse created = findAssignment(actor.tenantId(), id);
                    record(actor, "invoice_profile.assignment.created", "invoice_profile_assignment", id,
                            null, created, request.reason(), servletRequest);
                    return ResponseEntity.status(HttpStatus.CREATED).eTag(VersionEtag.format(0)).body(created);
                });
    }

    @DeleteMapping("/invoice-profiles/{profileId}/assignments/{assignmentId}")
    @PreAuthorize("hasAuthority('contract.write')")
    @Transactional
    public ResponseEntity<Void> removeAssignment(Authentication authentication, @PathVariable UUID profileId,
                                                 @PathVariable UUID assignmentId,
                                                 @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                 @RequestParam @NotBlank String reason,
                                                 HttpServletRequest servletRequest) {
        AuthenticatedUser actor = user(authentication);
        AssignmentResponse before = findAssignment(actor.tenantId(), assignmentId);
        if (!before.invoiceProfileId().equals(profileId)) {
            throw notFound("assignment_id", assignmentId);
        }
        long version = VersionEtag.parse(ifMatch);
        int changed = jdbc.sql("""
                        UPDATE invoice_profile_assignments SET status = 'INACTIVE',
                            effective_to = COALESCE(effective_to, now()), updated_at = now(), version = version + 1
                        WHERE tenant_id = :tenantId AND id = :id AND invoice_profile_id = :profileId
                          AND version = :version AND status = 'ACTIVE'
                        """)
                .param("tenantId", actor.tenantId()).param("id", assignmentId).param("profileId", profileId)
                .param("version", version).update();
        if (changed != 1) {
            throw new DomainException("VERSION_CONFLICT", "Invoice profile assignment was modified", 409,
                    Map.of("expected_version", version));
        }
        record(actor, "invoice_profile.assignment.removed", "invoice_profile_assignment", assignmentId,
                before, findAssignment(actor.tenantId(), assignmentId), reason, servletRequest);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/invoice-profiles/{id}/validate")
    @PreAuthorize("hasAnyAuthority('contract.write','preview.generate')")
    public ValidationResponse validate(Authentication authentication, @PathVariable UUID id) {
        findProfile(user(authentication).tenantId(), id);
        return validateProfile(user(authentication).tenantId(), id);
    }

    @PostMapping("/invoice-profiles/{id}/preview")
    @PreAuthorize("hasAuthority('preview.generate')")
    public ResponseEntity<JobAccepted> preview(Authentication authentication, @PathVariable UUID id,
                                               @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                               @Valid @RequestBody PreviewRequest request) {
        AuthenticatedUser actor = user(authentication);
        String path = "/api/v1/invoice-profiles/" + id + "/preview";
        PreviewCommand command = new PreviewCommand(id, request.periodStart(), request.periodEnd(),
                request.forceUsageSync(), actor.userId());
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, command,
                JobAccepted.class, () -> {
            ProfileResponse profile = findProfile(actor.tenantId(), id);
            if (!"ACTIVE".equals(profile.status())) {
                throw new DomainException("INVOICE_PROFILE_INACTIVE", "Only an active invoice profile can generate previews", 409,
                        Map.of("status", profile.status()));
            }
            ValidationResponse validation = validateProfile(actor.tenantId(), id);
            if (!validation.valid()) {
                throw new DomainException("INVOICE_PROFILE_INVALID", "Invoice profile validation failed", 422,
                        Map.of("errors", validation.errors()));
            }
            JsonNode payload = objectMapper.createObjectNode()
                    .put("invoice_profile_id", id.toString())
                    .put("period_start", request.periodStart().toString())
                    .put("period_end", request.periodEnd().toString())
                    .put("requested_by", actor.userId().toString())
                    .put("force_usage_sync", request.forceUsageSync());
            UUID jobId = jobs.enqueue(actor.tenantId(), "GENERATE_INVOICE_PREVIEW",
                    "preview:" + id + ":" + request.periodStart() + ":" + request.periodEnd(), payload);
            return ResponseEntity.accepted().body(new JobAccepted(jobId));
        });
    }

    private ValidationResponse validateProfile(UUID tenantId, UUID profileId) {
        ProfileResponse profile = findProfile(tenantId, profileId);
        List<String> errors = new ArrayList<>();
        try {
            requirePublishedTemplate(tenantId, profile.templateId());
        } catch (DomainException exception) {
            errors.add(exception.getMessage());
        }
        List<AssignmentResponse> assignments = assignments(tenantId, profileId).stream()
                .filter(assignment -> "ACTIVE".equals(assignment.status())).toList();
        if (assignments.isEmpty()) {
            errors.add("At least one active contract item assignment is required");
        }
        for (AssignmentResponse assignment : assignments) {
            try {
                validateItemBelongs(tenantId, profile, assignment.contractItemId());
            } catch (DomainException exception) {
                errors.add(exception.getMessage() + ": " + assignment.contractItemId());
            }
            if ("ALLOCATE_PERCENT".equals(assignment.assignmentMode())) {
                BigDecimal total = jdbc.sql("""
                                SELECT COALESCE(SUM(allocation_value), 0)
                                FROM invoice_profile_assignments
                                WHERE tenant_id = :tenantId AND contract_item_id = :itemId
                                  AND assignment_mode = 'ALLOCATE_PERCENT' AND status = 'ACTIVE'
                                  AND effective_to IS NULL
                                """)
                        .param("tenantId", tenantId).param("itemId", assignment.contractItemId())
                        .query(BigDecimal.class).single();
                if (total.compareTo(new BigDecimal("100")) != 0) {
                    errors.add("Percentage allocations for item " + assignment.contractItemId() + " total " + total + "%");
                }
            }
        }
        return new ValidationResponse(errors.isEmpty(), List.copyOf(errors));
    }

    private void validateItemBelongs(UUID tenantId, ProfileResponse profile, UUID itemId) {
        ItemOwner owner = jdbc.sql("""
                        SELECT contract.customer_id, contract.company_id, contract.currency_code
                        FROM contract_items item
                        JOIN contracts contract ON contract.id = item.contract_id AND contract.tenant_id = item.tenant_id
                        WHERE item.tenant_id = :tenantId AND item.id = :itemId
                        """)
                .param("tenantId", tenantId).param("itemId", itemId)
                .query((rs, row) -> new ItemOwner(rs.getObject("customer_id", UUID.class),
                        rs.getObject("company_id", UUID.class), rs.getString("currency_code")))
                .optional().orElseThrow(() -> notFound("contract_item_id", itemId));
        if (!profile.customerId().equals(owner.customerId()) || !profile.companyId().equals(owner.companyId())
                || !profile.currencyCode().equals(owner.currencyCode())) {
            throw new DomainException("INVOICE_PROFILE_RELATIONSHIP_MISMATCH",
                    "Contract item customer, company and currency must match the invoice profile", 422,
                    Map.of("contract_item_id", itemId));
        }
    }

    private UUID ensureDefaultWorkflow(UUID tenantId) {
        UUID existing = jdbc.sql("SELECT id FROM approval_workflows WHERE tenant_id = :tenantId AND workflow_code = :code")
                .param("tenantId", tenantId).param("code", DEFAULT_WORKFLOW).query(UUID.class).optional().orElse(null);
        if (existing != null) {
            return requireWorkflow(tenantId, existing);
        }
        UUID workflowId = UuidV7.generate();
        UUID versionId = UuidV7.generate();
        int inserted = jdbc.sql("""
                        INSERT INTO approval_workflows(id, tenant_id, workflow_code, workflow_name, status)
                        VALUES (:id, :tenantId, :code, '默认业务与财务两级审批', 'ACTIVE')
                        ON CONFLICT (tenant_id, workflow_code) DO NOTHING
                        """)
                .param("id", workflowId).param("tenantId", tenantId).param("code", DEFAULT_WORKFLOW).update();
        if (inserted == 0) {
            UUID concurrent = jdbc.sql("SELECT id FROM approval_workflows WHERE tenant_id = :tenantId AND workflow_code = :code")
                    .param("tenantId", tenantId).param("code", DEFAULT_WORKFLOW).query(UUID.class).single();
            return requireWorkflow(tenantId, concurrent);
        }
        jdbc.sql("""
                        INSERT INTO approval_workflow_versions(
                            id, tenant_id, workflow_id, version_no, conditions_json, status, published_at
                        ) VALUES (:id, :tenantId, :workflowId, 1, '{}'::jsonb, 'DRAFT', NULL)
                        """)
                .param("id", versionId).param("tenantId", tenantId).param("workflowId", workflowId).update();
        jdbc.sql("""
                        INSERT INTO approval_steps(
                            id, tenant_id, workflow_version_id, step_no, step_code, step_name, permission_code
                        ) VALUES
                            (:businessId, :tenantId, :versionId, 1, 'BUSINESS', '业务审核', 'preview.approve.business'),
                            (:financeId, :tenantId, :versionId, 2, 'FINANCE', '财务审核', 'preview.approve.finance')
                        """)
                .param("businessId", UuidV7.generate()).param("financeId", UuidV7.generate())
                .param("tenantId", tenantId).param("versionId", versionId).update();
        jdbc.sql("""
                        UPDATE approval_workflow_versions
                        SET status = 'PUBLISHED', published_at = now()
                        WHERE tenant_id = :tenantId AND id = :versionId AND status = 'DRAFT'
                        """)
                .param("tenantId", tenantId).param("versionId", versionId).update();
        jdbc.sql("""
                        UPDATE approval_workflows SET current_version_id = :versionId
                        WHERE tenant_id = :tenantId AND id = :workflowId
                        """)
                .param("versionId", versionId).param("tenantId", tenantId)
                .param("workflowId", workflowId).update();
        return workflowId;
    }

    private UUID requireWorkflow(UUID tenantId, UUID workflowId) {
        boolean exists = jdbc.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM approval_workflows workflow
                            JOIN approval_workflow_versions version ON version.id = workflow.current_version_id
                            WHERE workflow.tenant_id = :tenantId AND workflow.id = :id
                              AND workflow.status = 'ACTIVE' AND version.status = 'PUBLISHED'
                        )
                        """)
                .param("tenantId", tenantId).param("id", workflowId).query(Boolean.class).single();
        if (!exists) {
            throw new DomainException("APPROVAL_WORKFLOW_REQUIRED", "An active published approval workflow is required", 422,
                    Map.of("approval_workflow_id", workflowId));
        }
        return workflowId;
    }

    private void requirePublishedTemplate(UUID tenantId, UUID templateId) {
        boolean exists = jdbc.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM invoice_templates template
                            JOIN invoice_template_versions version ON version.id = template.current_version_id
                            WHERE template.tenant_id = :tenantId AND template.id = :id
                              AND template.status = 'ACTIVE' AND version.status = 'PUBLISHED'
                        )
                        """)
                .param("tenantId", tenantId).param("id", templateId).query(Boolean.class).single();
        if (!exists) {
            throw new DomainException("TEMPLATE_VERSION_REQUIRED", "Invoice profile requires an active published template", 422,
                    Map.of("template_id", templateId));
        }
    }

    private void requireCompany(UUID tenantId, UUID customerId, UUID companyId) {
        boolean exists = jdbc.sql("SELECT EXISTS(SELECT 1 FROM companies WHERE tenant_id = :tenantId AND id = :companyId AND customer_id = :customerId AND status = 'ACTIVE')")
                .param("tenantId", tenantId).param("companyId", companyId).param("customerId", customerId)
                .query(Boolean.class).single();
        if (!exists) {
            throw new DomainException("RELATIONSHIP_MISMATCH", "Active company must belong to the selected customer", 422,
                    Map.of("customer_id", customerId, "company_id", companyId));
        }
    }

    private ProfileResponse findProfile(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM invoice_profiles WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::mapProfile).optional()
                .orElseThrow(() -> notFound("invoice_profile_id", id));
    }

    private AssignmentResponse findAssignment(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT * FROM invoice_profile_assignments WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", id).query(this::mapAssignment).optional()
                .orElseThrow(() -> notFound("assignment_id", id));
    }

    private List<AssignmentResponse> assignments(UUID tenantId, UUID profileId) {
        return jdbc.sql("""
                        SELECT * FROM invoice_profile_assignments
                        WHERE tenant_id = :tenantId AND invoice_profile_id = :profileId
                        ORDER BY sort_order, created_at
                        """)
                .param("tenantId", tenantId).param("profileId", profileId).query(this::mapAssignment).list();
    }

    private ProfileResponse mapProfile(ResultSet rs, int row) throws SQLException {
        return new ProfileResponse(rs.getObject("id", UUID.class), rs.getString("profile_code"),
                rs.getString("profile_name"), rs.getObject("customer_id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getObject("template_id", UUID.class),
                rs.getObject("approval_workflow_id", UUID.class), rs.getString("language"),
                rs.getString("currency_code"), rs.getString("timezone"), rs.getString("billing_cycle"),
                rs.getObject("billing_day", Integer.class), rs.getInt("payment_terms_days"),
                rs.getString("tax_calculation_mode"), rs.getString("invoice_number_rule"),
                parseJson(rs.getString("payment_account_json")), parseJson(rs.getString("recipients_json")),
                rs.getBoolean("auto_generate"), rs.getBoolean("auto_submit_review"), rs.getBoolean("auto_send"),
                rs.getString("status"), rs.getString("notes"), rs.getLong("version"));
    }

    private AssignmentResponse mapAssignment(ResultSet rs, int row) throws SQLException {
        return new AssignmentResponse(rs.getObject("id", UUID.class), rs.getObject("invoice_profile_id", UUID.class),
                rs.getObject("contract_item_id", UUID.class), rs.getString("assignment_mode"),
                rs.getBigDecimal("allocation_value"), rs.getObject("effective_from", OffsetDateTime.class),
                rs.getObject("effective_to", OffsetDateTime.class), rs.getInt("sort_order"),
                rs.getString("reason"), rs.getString("status"), rs.getLong("version"));
    }

    private JsonNode parseJson(String value) throws SQLException {
        try {
            return objectMapper.readTree(value == null ? "{}" : value);
        } catch (Exception exception) {
            throw new SQLException("Invalid invoice profile JSON", exception);
        }
    }

    private String jsonObject(JsonNode node) {
        return node == null || node.isNull() ? "{}" : node.toString();
    }

    private String jsonArray(JsonNode node) {
        return node == null || node.isNull() ? "[]" : node.toString();
    }

    private String nullableJson(JsonNode node) {
        return node == null || node.isNull() ? null : node.toString();
    }

    private AuthenticatedUser user(Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private DomainException notFound(String field, UUID id) {
        return new DomainException("RESOURCE_NOT_FOUND", "Resource was not found", 404, Map.of(field, id));
    }

    private void record(AuthenticatedUser actor, String action, String type, UUID id, Object before,
                        Object after, String reason, HttpServletRequest request) {
        audit.record(actor.tenantId(), "USER", actor.userId(), actor.displayName(), action, type, id,
                before, after, reason, request.getHeader("X-Request-Id"));
    }

    public record ProfileCreateRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{2,99}") String profileCode,
            @NotBlank String profileName, @NotNull UUID customerId, @NotNull UUID companyId,
            @NotNull UUID templateId, UUID approvalWorkflowId, @NotBlank String language,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currencyCode, @NotBlank String timezone,
            @NotBlank String billingCycle, @Min(1) @Max(28) Integer billingDay,
            @PositiveOrZero int paymentTermsDays, @NotBlank String taxCalculationMode,
            @NotBlank String invoiceNumberRule, JsonNode paymentAccount, JsonNode recipients,
            boolean autoGenerate, boolean autoSubmitReview, boolean autoSend,
            String notes, @NotBlank String reason) {
    }

    public record ProfileUpdateRequest(String profileName, UUID templateId, String language, String timezone,
                                       @Min(1) @Max(28) Integer billingDay,
                                       @PositiveOrZero Integer paymentTermsDays, String invoiceNumberRule,
                                       JsonNode paymentAccount, JsonNode recipients, Boolean autoGenerate,
                                       Boolean autoSubmitReview, Boolean autoSend, String status,
                                       String notes, @NotBlank String reason) {
    }

    public record AssignmentCreateRequest(@NotNull UUID contractItemId, @NotBlank String assignmentMode,
                                          BigDecimal allocationValue, @NotNull OffsetDateTime effectiveFrom,
                                          OffsetDateTime effectiveTo, int sortOrder, @NotBlank String reason) {
    }

    public record PreviewRequest(@NotNull OffsetDateTime periodStart, @NotNull OffsetDateTime periodEnd,
                                 boolean forceUsageSync) {
    }

    public record ProfileResponse(UUID id, String profileCode, String profileName, UUID customerId,
                                  UUID companyId, UUID templateId, UUID approvalWorkflowId, String language,
                                  String currencyCode, String timezone, String billingCycle, Integer billingDay,
                                  int paymentTermsDays, String taxCalculationMode, String invoiceNumberRule,
                                  JsonNode paymentAccount, JsonNode recipients, boolean autoGenerate,
                                  boolean autoSubmitReview, boolean autoSend, String status, String notes,
                                  long version) {
    }

    public record AssignmentResponse(UUID id, UUID invoiceProfileId, UUID contractItemId, String assignmentMode,
                                     BigDecimal allocationValue, OffsetDateTime effectiveFrom,
                                     OffsetDateTime effectiveTo, int sortOrder, String reason,
                                     String status, long version) {
    }

    public record ProfileDetail(ProfileResponse profile, List<AssignmentResponse> assignments,
                                ValidationResponse validation) {
    }

    public record ValidationResponse(boolean valid, List<String> errors) {
    }

    public record JobAccepted(UUID jobId) {
    }

    private record ItemOwner(UUID customerId, UUID companyId, String currencyCode) {
    }

    private record PreviewCommand(UUID invoiceProfileId, OffsetDateTime periodStart, OffsetDateTime periodEnd,
                                  boolean forceUsageSync, UUID requestedBy) {
    }
}
