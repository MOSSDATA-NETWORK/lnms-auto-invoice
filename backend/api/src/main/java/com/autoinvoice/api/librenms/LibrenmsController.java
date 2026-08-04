package com.autoinvoice.api.librenms;

import com.autoinvoice.api.http.VersionEtag;
import com.autoinvoice.api.idempotency.IdempotencyExecutor;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.audit.AuditService;
import com.autoinvoice.platform.jobs.BackgroundJobService;
import com.autoinvoice.platform.security.SecretCipher;
import com.autoinvoice.usage.LibrenmsOriginPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/librenms/instances")
public class LibrenmsController {
    private final JdbcClient jdbc;
    private final SecretCipher secretCipher;
    private final IdempotencyExecutor idempotency;
    private final BackgroundJobService jobs;
    private final ObjectMapper objectMapper;
    private final AuditService audit;
    private final LibrenmsOriginPolicy originPolicy;

    public LibrenmsController(JdbcClient jdbc, SecretCipher secretCipher, IdempotencyExecutor idempotency,
                              BackgroundJobService jobs, ObjectMapper objectMapper, AuditService audit,
                              LibrenmsOriginPolicy originPolicy) {
        this.jdbc = jdbc;
        this.secretCipher = secretCipher;
        this.idempotency = idempotency;
        this.jobs = jobs;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.originPolicy = originPolicy;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('usage.sync')")
    public List<InstanceResponse> list(Authentication authentication) {
        return jdbc.sql("""
                        SELECT id, instance_code, instance_name, base_url, timezone, connect_timeout_ms,
                               read_timeout_ms, max_concurrency, tls_verify, status, last_success_at,
                               last_failure_at, consecutive_failures, version
                        FROM librenms_instances WHERE tenant_id = :tenantId ORDER BY instance_code
                        """)
                .param("tenantId", principal(authentication).tenantId()).query(this::mapInstance).list();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system.admin')")
    public ResponseEntity<InstanceResponse> create(Authentication authentication,
                                                   @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                   @Valid @RequestBody CreateInstanceRequest request,
                                                   HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        String baseUrl = allowedBaseUrl(request.baseUrl());
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST",
                "/api/v1/librenms/instances", request,
                InstanceResponse.class, () -> {
                    UUID id = UuidV7.generate();
                    String ciphertext = secretCipher.encrypt(request.apiToken(), actor.tenantId(),
                            "librenms-instance:" + id);
                    jdbc.sql("""
                                    INSERT INTO librenms_instances(
                                        id, tenant_id, instance_code, instance_name, base_url, api_token_ciphertext,
                                        timezone, connect_timeout_ms, read_timeout_ms, max_concurrency, tls_verify
                                    ) VALUES (
                                        :id, :tenantId, :code, :name, :baseUrl, :token,
                                        :timezone, :connectTimeout, :readTimeout, :maxConcurrency, true
                                    )
                                    """)
                            .param("id", id).param("tenantId", actor.tenantId()).param("code", request.instanceCode())
                            .param("name", request.instanceName()).param("baseUrl", baseUrl)
                            .param("token", ciphertext).param("timezone", request.timezone())
                            .param("connectTimeout", request.connectTimeoutMs()).param("readTimeout", request.readTimeoutMs())
                            .param("maxConcurrency", request.maxConcurrency()).update();
                    InstanceResponse created = findInstance(actor.tenantId(), id);
                    record(actor, "librenms.instance.created", "librenms_instance", id, null, created,
                            request.reason(), servletRequest);
                    return ResponseEntity.status(201).eTag(VersionEtag.format(0)).body(created);
                });
    }

    @PostMapping("/{instanceId}/verify")
    @PreAuthorize("hasAuthority('usage.sync')")
    public ResponseEntity<JobAccepted> verify(Authentication authentication, @PathVariable UUID instanceId,
                                              @RequestHeader(IdempotencyExecutor.HEADER) String key) {
        AuthenticatedUser actor = principal(authentication);
        InstanceResponse instance = findInstance(actor.tenantId(), instanceId);
        InstanceCommand command = new InstanceCommand(instanceId);
        String path = "/api/v1/librenms/instances/" + instanceId + "/verify";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, command,
                JobAccepted.class, () -> {
            JsonNode payload = objectMapper.createObjectNode().put("instance_id", instanceId.toString());
            UUID jobId = jobs.enqueue(actor.tenantId(), "LIBRENMS_VERIFY",
                    "librenms-verify:" + instanceId + ":" + instance.version(), payload);
            return ResponseEntity.accepted().body(new JobAccepted(jobId));
        });
    }

    @PostMapping("/{instanceId}/discover-bills")
    @PreAuthorize("hasAuthority('usage.sync')")
    public ResponseEntity<JobAccepted> discover(Authentication authentication, @PathVariable UUID instanceId,
                                                @RequestHeader(IdempotencyExecutor.HEADER) String key) {
        AuthenticatedUser actor = principal(authentication);
        InstanceResponse instance = findInstance(actor.tenantId(), instanceId);
        InstanceCommand command = new InstanceCommand(instanceId);
        String path = "/api/v1/librenms/instances/" + instanceId + "/discover-bills";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, command,
                JobAccepted.class, () -> {
            JsonNode payload = objectMapper.createObjectNode().put("instance_id", instanceId.toString());
            UUID jobId = jobs.enqueue(actor.tenantId(), "LIBRENMS_DISCOVER_BILLS",
                    "librenms-discover:" + instanceId + ":" + instance.version(), payload);
            return ResponseEntity.accepted().body(new JobAccepted(jobId));
        });
    }

    @GetMapping("/{instanceId}/discovered-bills")
    @PreAuthorize("hasAuthority('usage.sync')")
    public List<DiscoveredBillResponse> discovered(Authentication authentication, @PathVariable UUID instanceId) {
        UUID tenantId = principal(authentication).tenantId();
        findInstance(tenantId, instanceId);
        return jdbc.sql("""
                        SELECT discovered.*,
                               EXISTS(SELECT 1 FROM librenms_bill_mappings mapping
                                      WHERE mapping.tenant_id = discovered.tenant_id
                                        AND mapping.librenms_instance_id = discovered.librenms_instance_id
                                        AND mapping.librenms_bill_id = discovered.librenms_bill_id
                                        AND mapping.status = 'ACTIVE') AS mapped
                        FROM librenms_discovered_bills discovered
                        WHERE discovered.tenant_id = :tenantId AND discovered.librenms_instance_id = :instanceId
                        ORDER BY discovered.bill_name NULLS LAST, discovered.librenms_bill_id
                        """)
                .param("tenantId", tenantId).param("instanceId", instanceId).query(this::mapDiscovered).list();
    }

    @GetMapping("/{instanceId}/mappings")
    @PreAuthorize("hasAuthority('usage.sync')")
    public List<MappingResponse> mappings(Authentication authentication, @PathVariable UUID instanceId) {
        UUID tenantId = principal(authentication).tenantId();
        findInstance(tenantId, instanceId);
        return jdbc.sql("""
                        SELECT mapping.*, item.contract_item_no, item.item_name, service.service_no, service.service_name
                        FROM librenms_bill_mappings mapping
                        JOIN contract_items item ON item.tenant_id = mapping.tenant_id AND item.id = mapping.contract_item_id
                        JOIN services service ON service.tenant_id = mapping.tenant_id AND service.id = mapping.service_id
                        WHERE mapping.tenant_id = :tenantId AND mapping.librenms_instance_id = :instanceId
                        ORDER BY mapping.created_at DESC
                        """)
                .param("tenantId", tenantId).param("instanceId", instanceId).query(this::mapMapping).list();
    }

    @PostMapping("/{instanceId}/mappings")
    @PreAuthorize("hasAuthority('usage.sync')")
    public ResponseEntity<MappingResponse> createMapping(Authentication authentication,
                                                         @PathVariable UUID instanceId,
                                                         @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                         @Valid @RequestBody MappingCreateRequest request,
                                                         HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        String path = "/api/v1/librenms/instances/" + instanceId + "/mappings";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                MappingResponse.class,
                () -> createMapping(actor, instanceId, request, servletRequest));
    }

    @Transactional
    protected ResponseEntity<MappingResponse> createMapping(AuthenticatedUser actor, UUID instanceId,
                                                             MappingCreateRequest request,
                                                             HttpServletRequest servletRequest) {
        findInstance(actor.tenantId(), instanceId);
        DiscoveredBillResponse discovered = findDiscovered(actor.tenantId(), instanceId, request.librenmsBillId());
        requireMappingTargets(actor.tenantId(), request);
        UUID id = UuidV7.generate();
        jdbc.sql("""
                        INSERT INTO librenms_bill_mappings(
                            id, tenant_id, librenms_instance_id, librenms_bill_id,
                            observed_bill_name, observed_bill_ref, observed_bill_custid,
                            customer_id, company_id, service_id, contract_item_id,
                            billing_direction, source_unit, effective_from, effective_to,
                            discovery_status, status
                        ) VALUES (
                            :id, :tenantId, :instanceId, :billId,
                            :billName, :billRef, :billCustid,
                            :customerId, :companyId, :serviceId, :contractItemId,
                            :direction, :sourceUnit, :effectiveFrom, :effectiveTo,
                            'CONFIRMED', 'ACTIVE'
                        )
                        """)
                .param("id", id).param("tenantId", actor.tenantId()).param("instanceId", instanceId)
                .param("billId", request.librenmsBillId()).param("billName", discovered.billName())
                .param("billRef", discovered.billRef()).param("billCustid", discovered.billCustid())
                .param("customerId", request.customerId()).param("companyId", request.companyId())
                .param("serviceId", request.serviceId()).param("contractItemId", request.contractItemId())
                .param("direction", request.billingDirection()).param("sourceUnit", request.sourceUnit())
                .param("effectiveFrom", request.effectiveFrom()).param("effectiveTo", request.effectiveTo()).update();
        MappingResponse created = findMapping(actor.tenantId(), instanceId, id);
        record(actor, "librenms.mapping.created", "librenms_bill_mapping", id, null, created,
                request.reason(), servletRequest);
        return ResponseEntity.status(201).eTag(VersionEtag.format(0)).body(created);
    }

    @PostMapping("/{instanceId}/mappings/{mappingId}/deactivate")
    @PreAuthorize("hasAuthority('usage.sync')")
    public ResponseEntity<MappingResponse> deactivate(Authentication authentication,
                                                       @PathVariable UUID instanceId, @PathVariable UUID mappingId,
                                                       @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                       @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                                       @Valid @RequestBody VersionedReasonRequest request,
                                                       HttpServletRequest servletRequest) {
        AuthenticatedUser actor = principal(authentication);
        assertVersion(ifMatch, request.expectedVersion());
        String path = "/api/v1/librenms/instances/" + instanceId + "/mappings/" + mappingId + "/deactivate";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                MappingResponse.class, () -> {
            MappingResponse before = findMapping(actor.tenantId(), instanceId, mappingId);
            int changed = jdbc.sql("""
                            UPDATE librenms_bill_mappings SET status = 'INACTIVE',
                                effective_to = COALESCE(effective_to, now()), updated_at = now(), version = version + 1
                            WHERE tenant_id = :tenantId AND librenms_instance_id = :instanceId AND id = :mappingId
                              AND status = 'ACTIVE' AND version = :version
                            """)
                    .param("tenantId", actor.tenantId()).param("instanceId", instanceId).param("mappingId", mappingId)
                    .param("version", request.expectedVersion()).update();
            if (changed != 1) {
                throw versionConflict(request.expectedVersion());
            }
            MappingResponse after = findMapping(actor.tenantId(), instanceId, mappingId);
            record(actor, "librenms.mapping.deactivated", "librenms_bill_mapping", mappingId, before, after,
                    request.reason(), servletRequest);
            return ResponseEntity.ok().eTag(VersionEtag.format(after.version())).body(after);
        });
    }

    @PostMapping("/{instanceId}/mappings/{mappingId}/sync-history")
    @PreAuthorize("hasAuthority('usage.sync')")
    public ResponseEntity<JobAccepted> syncHistory(Authentication authentication, @PathVariable UUID instanceId,
                                                   @PathVariable UUID mappingId,
                                                   @RequestHeader(IdempotencyExecutor.HEADER) String key,
                                                   @Valid @RequestBody HistorySyncRequest request) {
        AuthenticatedUser actor = principal(authentication);
        MappingResponse mapping = findMapping(actor.tenantId(), instanceId, mappingId);
        if (!"ACTIVE".equals(mapping.status())) {
            throw new DomainException("LIBRENMS_MAPPING_INACTIVE", "Only active mappings can synchronize usage", 409,
                    Map.of("mapping_id", mappingId));
        }
        String path = "/api/v1/librenms/instances/" + instanceId + "/mappings/" + mappingId + "/sync-history";
        return idempotency.execute(actor.tenantId(), actor.userId(), key, "POST", path, request,
                JobAccepted.class, () -> {
            JsonNode payload = objectMapper.createObjectNode().put("mapping_id", mappingId.toString())
                    .put("period_start", request.periodStart().toString()).put("period_end", request.periodEnd().toString())
                    .put("requested_by", actor.userId().toString());
            UUID jobId = jobs.enqueue(actor.tenantId(), "LIBRENMS_SYNC_HISTORY",
                    "SYNC_USAGE:" + instanceId + ":" + mapping.librenmsBillId() + ":"
                            + request.periodStart() + ":" + request.periodEnd() + ":PREVIEW", payload);
            return ResponseEntity.accepted().body(new JobAccepted(jobId));
        });
    }

    private void requireMappingTargets(UUID tenantId, MappingCreateRequest request) {
        boolean valid = jdbc.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM contract_items item
                            JOIN contracts contract ON contract.tenant_id = item.tenant_id AND contract.id = item.contract_id
                            JOIN services service ON service.tenant_id = item.tenant_id AND service.id = item.service_id
                            WHERE item.tenant_id = :tenantId AND item.id = :contractItemId
                              AND item.service_id = :serviceId AND service.customer_id = :customerId
                              AND service.company_id = :companyId AND contract.customer_id = :customerId
                              AND contract.company_id = :companyId
                        )
                        """)
                .param("tenantId", tenantId).param("contractItemId", request.contractItemId())
                .param("serviceId", request.serviceId()).param("customerId", request.customerId())
                .param("companyId", request.companyId()).query(Boolean.class).single();
        if (!valid) {
            throw new DomainException("LIBRENMS_MAPPING_TARGET_MISMATCH",
                    "Customer, company, service and contract item must describe the same tenant-owned service", 422,
                    Map.of("contract_item_id", request.contractItemId()));
        }
    }

    private InstanceResponse findInstance(UUID tenantId, UUID id) {
        return jdbc.sql("""
                        SELECT id, instance_code, instance_name, base_url, timezone, connect_timeout_ms,
                               read_timeout_ms, max_concurrency, tls_verify, status, last_success_at,
                               last_failure_at, consecutive_failures, version
                        FROM librenms_instances WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId).param("id", id).query(this::mapInstance).optional()
                .orElseThrow(() -> notFound("instance_id", id));
    }

    private DiscoveredBillResponse findDiscovered(UUID tenantId, UUID instanceId, long billId) {
        return jdbc.sql("""
                        SELECT discovered.*, false AS mapped FROM librenms_discovered_bills discovered
                        WHERE tenant_id = :tenantId AND librenms_instance_id = :instanceId
                          AND librenms_bill_id = :billId
                        """)
                .param("tenantId", tenantId).param("instanceId", instanceId).param("billId", billId)
                .query(this::mapDiscovered).optional()
                .orElseThrow(() -> new DomainException("LIBRENMS_BILL_NOT_DISCOVERED",
                        "LibreNMS Bill must be discovered before mapping", 422, Map.of("librenms_bill_id", billId)));
    }

    private MappingResponse findMapping(UUID tenantId, UUID instanceId, UUID mappingId) {
        return jdbc.sql("""
                        SELECT mapping.*, item.contract_item_no, item.item_name, service.service_no, service.service_name
                        FROM librenms_bill_mappings mapping
                        JOIN contract_items item ON item.tenant_id = mapping.tenant_id AND item.id = mapping.contract_item_id
                        JOIN services service ON service.tenant_id = mapping.tenant_id AND service.id = mapping.service_id
                        WHERE mapping.tenant_id = :tenantId AND mapping.librenms_instance_id = :instanceId
                          AND mapping.id = :mappingId
                        """)
                .param("tenantId", tenantId).param("instanceId", instanceId).param("mappingId", mappingId)
                .query(this::mapMapping).optional().orElseThrow(() -> notFound("mapping_id", mappingId));
    }

    private InstanceResponse mapInstance(ResultSet rs, int row) throws SQLException {
        return new InstanceResponse(rs.getObject("id", UUID.class), rs.getString("instance_code"),
                rs.getString("instance_name"), rs.getString("base_url"), rs.getString("timezone"),
                rs.getInt("connect_timeout_ms"), rs.getInt("read_timeout_ms"), rs.getInt("max_concurrency"),
                rs.getBoolean("tls_verify"), rs.getString("status"), rs.getObject("last_success_at", OffsetDateTime.class),
                rs.getObject("last_failure_at", OffsetDateTime.class), rs.getInt("consecutive_failures"), rs.getLong("version"));
    }

    private DiscoveredBillResponse mapDiscovered(ResultSet rs, int row) throws SQLException {
        return new DiscoveredBillResponse(rs.getObject("id", UUID.class), rs.getLong("librenms_bill_id"),
                rs.getString("bill_name"), rs.getString("bill_ref"), rs.getString("bill_custid"),
                rs.getString("bill_type"), rs.getString("bill_state"), json(rs.getString("source_payload_json")),
                rs.getString("response_hash"), rs.getObject("last_seen_at", OffsetDateTime.class),
                rs.getBoolean("mapped"), rs.getLong("version"));
    }

    private MappingResponse mapMapping(ResultSet rs, int row) throws SQLException {
        return new MappingResponse(rs.getObject("id", UUID.class), rs.getObject("librenms_instance_id", UUID.class),
                rs.getLong("librenms_bill_id"), rs.getString("observed_bill_name"),
                rs.getObject("customer_id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("service_id", UUID.class), rs.getString("service_no"), rs.getString("service_name"),
                rs.getObject("contract_item_id", UUID.class), rs.getString("contract_item_no"),
                rs.getString("item_name"), rs.getString("billing_direction"), rs.getString("source_unit"),
                rs.getObject("effective_from", OffsetDateTime.class), rs.getObject("effective_to", OffsetDateTime.class),
                rs.getString("discovery_status"), rs.getString("status"), rs.getLong("version"));
    }

    private JsonNode json(String value) throws SQLException {
        try {
            return objectMapper.readTree(value == null ? "{}" : value);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Persisted LibreNMS JSON is invalid", exception);
        }
    }

    String allowedBaseUrl(String value) {
        return originPolicy.requireAllowed(value).toString();
    }

    private void assertVersion(String ifMatch, long bodyVersion) {
        long header = VersionEtag.parse(ifMatch);
        if (header != bodyVersion) {
            throw versionConflict(bodyVersion);
        }
    }

    private DomainException versionConflict(long version) {
        return new DomainException("VERSION_CONFLICT", "LibreNMS mapping was modified by another request", 409,
                Map.of("expected_version", version));
    }

    private AuthenticatedUser principal(Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    private void record(AuthenticatedUser actor, String action, String type, UUID id, Object before,
                        Object after, String reason, HttpServletRequest request) {
        audit.record(actor.tenantId(), "USER", actor.userId(), actor.displayName(), action, type, id,
                before, after, reason, request.getHeader("X-Request-Id"));
    }

    private DomainException notFound(String field, UUID id) {
        return new DomainException("RESOURCE_NOT_FOUND", "Resource was not found", 404, Map.of(field, id));
    }

    public record CreateInstanceRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9][A-Z0-9_-]{2,99}") String instanceCode,
            @NotBlank String instanceName, @NotBlank String baseUrl, @NotBlank String apiToken,
            @NotBlank String timezone, @Min(100) @Max(60_000) int connectTimeoutMs,
            @Min(500) @Max(300_000) int readTimeoutMs, @Min(1) @Max(100) int maxConcurrency,
            @NotBlank String reason) {
    }

    public record MappingCreateRequest(long librenmsBillId, @NotNull UUID customerId, @NotNull UUID companyId,
                                       @NotNull UUID serviceId, @NotNull UUID contractItemId,
                                       @NotBlank @Pattern(regexp = "MAX|INBOUND|OUTBOUND|AGGREGATE|LIBRENMS_FINAL") String billingDirection,
                                       String sourceUnit, @NotNull OffsetDateTime effectiveFrom,
                                       OffsetDateTime effectiveTo, @NotBlank String reason) {
    }

    public record HistorySyncRequest(@NotNull OffsetDateTime periodStart, @NotNull OffsetDateTime periodEnd) {
        public HistorySyncRequest {
            if (periodStart != null && periodEnd != null && !periodStart.isBefore(periodEnd)) {
                throw new IllegalArgumentException("period_end must be after period_start");
            }
        }
    }

    public record VersionedReasonRequest(@PositiveOrZero long expectedVersion, @NotBlank String reason) {
    }

    public record InstanceResponse(UUID id, String instanceCode, String instanceName, String baseUrl,
                                   String timezone, int connectTimeoutMs, int readTimeoutMs, int maxConcurrency,
                                   boolean tlsVerify, String status, OffsetDateTime lastSuccessAt,
                                   OffsetDateTime lastFailureAt, int consecutiveFailures, long version) {
    }

    public record DiscoveredBillResponse(UUID id, long librenmsBillId, String billName, String billRef,
                                         String billCustid, String billType, String billState, JsonNode sourcePayload,
                                         String responseHash, OffsetDateTime lastSeenAt, boolean mapped, long version) {
    }

    public record MappingResponse(UUID id, UUID librenmsInstanceId, long librenmsBillId,
                                  String observedBillName, UUID customerId, UUID companyId, UUID serviceId,
                                  String serviceNo, String serviceName, UUID contractItemId,
                                  String contractItemNo, String itemName, String billingDirection, String sourceUnit,
                                  OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo,
                                  String discoveryStatus, String status, long version) {
    }

    public record JobAccepted(UUID jobId) {
    }

    private record InstanceCommand(UUID instanceId) {
    }
}
