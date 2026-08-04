package com.autoinvoice.api.storage;

import com.autoinvoice.api.idempotency.IdempotencyExecutor;
import com.autoinvoice.api.imports.ImportController;
import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.invoice.InvoicePreviewWorkflowService;
import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.audit.AuditService;
import com.autoinvoice.platform.jobs.BackgroundJobService;
import com.autoinvoice.platform.security.SecretCipher;
import com.autoinvoice.platform.storage.FileReferencePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class FileReferenceAuthorizationIntegrationTest {
    private static final String MASTER_KEY = java.util.Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.9-alpine");

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static ObjectMapper objectMapper;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void sameTenantAttackerCannotUseVictimFileToCreateAReadableBusinessReference() throws Exception {
        Fixture fixture = fixture();
        UUID victimFileId = insertFile(fixture.tenantId(), fixture.victim().userId(), "victim.csv");
        UUID previewId = insertPreview(fixture.tenantId(), fixture.attacker().userId());
        ImportController imports = importController();
        ImportController.ImportCreateRequest importRequest = new ImportController.ImportCreateRequest(
                "CUSTOMERS", victimFileId, objectMapper.createObjectNode(), "attempted file claim");

        assertResourceNotFound(() -> inTransaction(() -> imports.create(
                authentication(fixture.attacker()), "claim-import-" + UUID.randomUUID(), importRequest,
                request("POST", "/api/v1/imports/master-data"))));

        InvoicePreviewWorkflowService previews = new InvoicePreviewWorkflowService(
                jdbc, objectMapper, new FileReferencePolicy(jdbc));
        assertResourceNotFound(() -> transactions.executeWithoutResult(status -> previews.addAdjustment(
                fixture.tenantId(), previewId, 0, fixture.attacker().userId(), fixture.attacker().permissions(),
                "SURCHARGE", "Attempted attachment claim", 100, null, true,
                "attempted file claim", victimFileId)));

        assertThat(jdbc.sql("SELECT count(*) FROM import_jobs WHERE source_file_id = :fileId")
                .param("fileId", victimFileId).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM invoice_preview_adjustments
                        WHERE attachment_file_id = :fileId
                        """)
                .param("fileId", victimFileId).query(Integer.class).single()).isZero();
        assertResourceNotFound(() -> fileController(mock(ApiObjectStorage.class))
                .metadata(authentication(fixture.attacker()), victimFileId));
    }

    @Test
    void identicalUploadsRemainOwnedAndAssociableByEachUploader() throws Exception {
        Fixture fixture = fixture();
        ApiObjectStorage storage = mock(ApiObjectStorage.class);
        when(storage.put(anyString(), any(byte[].class), anyString())).thenAnswer(invocation ->
                new ApiObjectStorage.StoredObject("MINIO", "file-acl-test", invocation.getArgument(0)));
        FileController files = fileController(storage);
        byte[] csv = "customer_no,customer_name\nC-1,Example\n".getBytes(StandardCharsets.UTF_8);

        FileController.FileResponse victimUpload = upload(files, fixture.victim(), csv, "master.csv");
        FileController.FileResponse attackerUpload = upload(files, fixture.attacker(), csv, "master.csv");

        assertThat(attackerUpload.id()).isNotEqualTo(victimUpload.id());
        assertThat(fileOwner(victimUpload.id())).isEqualTo(fixture.victim().userId());
        assertThat(fileOwner(attackerUpload.id())).isEqualTo(fixture.attacker().userId());

        ImportController imports = importController();
        createImport(imports, fixture.victim(), victimUpload.id(), "victim-owned-upload");
        createImport(imports, fixture.attacker(), attackerUpload.id(), "attacker-owned-upload");

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM import_jobs
                        WHERE tenant_id = :tenantId AND source_file_id IN (:fileIds)
                        """)
                .param("tenantId", fixture.tenantId())
                .param("fileIds", Set.of(victimUpload.id(), attackerUpload.id()))
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void systemAdministratorCanAssociateAnotherUsersFile() {
        Fixture fixture = fixture();
        UUID victimFileId = insertFile(fixture.tenantId(), fixture.victim().userId(), "admin.csv");

        ImportController.ImportAccepted accepted = createImport(
                importController(), fixture.administrator(), victimFileId, "admin-authorized-reference");

        assertThat(accepted).isNotNull();
        assertThat(jdbc.sql("SELECT requested_by FROM import_jobs WHERE id = :id")
                .param("id", accepted.importId()).query(UUID.class).single())
                .isEqualTo(fixture.administrator().userId());
    }

    private ImportController.ImportAccepted createImport(ImportController controller, AuthenticatedUser actor,
                                                          UUID fileId, String keyLabel) {
        ImportController.ImportCreateRequest command = new ImportController.ImportCreateRequest(
                "CUSTOMERS", fileId, objectMapper.createObjectNode(), "authorized import");
        var response = inTransaction(() -> controller.create(
                authentication(actor), keyLabel + "-" + UUID.randomUUID(), command,
                request("POST", "/api/v1/imports/master-data")));
        return response.getBody();
    }

    private FileController.FileResponse upload(FileController controller, AuthenticatedUser actor,
                                                byte[] bytes, String filename) throws Exception {
        Authentication authentication = authentication(actor);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            return inTransaction(() -> {
                try {
                    var response = controller.upload(authentication, "upload-" + UUID.randomUUID(),
                            new MockMultipartFile("file", filename, "text/csv", bytes),
                            "file ownership test", request("POST", "/api/v1/files"));
                    return response.getBody();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private ImportController importController() {
        BackgroundJobService jobs = mock(BackgroundJobService.class);
        when(jobs.enqueue(any(UUID.class), anyString(), anyString(), any())).thenAnswer(invocation -> UUID.randomUUID());
        return new ImportController(jdbc, objectMapper, jobs, idempotency(), mock(AuditService.class),
                new FileReferencePolicy(jdbc));
    }

    private FileController fileController(ApiObjectStorage storage) {
        return new FileController(jdbc, storage, idempotency(), mock(AuditService.class));
    }

    private IdempotencyExecutor idempotency() {
        return new IdempotencyExecutor(jdbc, objectMapper, new SecretCipher(MASTER_KEY), MASTER_KEY);
    }

    private Fixture fixture() {
        UUID tenantId = UUID.randomUUID();
        String suffix = compact(tenantId).substring(0, 12);
        jdbc.sql("INSERT INTO tenants(id, tenant_code, tenant_name) VALUES (:id, :code, 'File ACL tenant')")
                .param("id", tenantId).param("code", "acl_" + suffix).update();
        AuthenticatedUser victim = user(tenantId, "victim_" + suffix,
                Set.of("customer.write", "preview.adjust"));
        AuthenticatedUser attacker = user(tenantId, "attacker_" + suffix,
                Set.of("customer.write", "preview.adjust"));
        AuthenticatedUser administrator = user(tenantId, "admin_" + suffix, Set.of("system.admin"));
        return new Fixture(tenantId, victim, attacker, administrator);
    }

    private AuthenticatedUser user(UUID tenantId, String username, Set<String> permissions) {
        UUID userId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO users(id, tenant_id, username, email, display_name, status)
                        VALUES (:id, :tenantId, :username, :email, :displayName, 'ACTIVE')
                        """)
                .param("id", userId).param("tenantId", tenantId).param("username", username)
                .param("email", username + "@example.invalid").param("displayName", username).update();
        return new AuthenticatedUser(userId, tenantId, "file-acl", username, username,
                "", false, null, false, 1, permissions, true);
    }

    private UUID insertFile(UUID tenantId, UUID createdBy, String filename) {
        UUID fileId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO files(
                            id, tenant_id, storage_provider, bucket_name, object_key,
                            original_filename, mime_type, file_size, sha256, created_by
                        ) VALUES (
                            :id, :tenantId, 'MINIO', 'file-acl-test', :objectKey,
                            :filename, 'text/csv', 4, :sha256, :createdBy
                        )
                        """)
                .param("id", fileId).param("tenantId", tenantId)
                .param("objectKey", tenantId + "/tests/" + fileId)
                .param("filename", filename).param("sha256", "a".repeat(64))
                .param("createdBy", createdBy).update();
        return fileId;
    }

    private UUID insertPreview(UUID tenantId, UUID createdBy) throws Exception {
        UUID previewId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
            }
            JdbcClient local = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            local.sql("""
                            INSERT INTO invoice_previews(
                                id, tenant_id, preview_number, invoice_profile_id, customer_id, company_id,
                                template_id, template_version_id, period_start, period_end, issue_date, due_date,
                                timezone, language, currency_code, profile_snapshot_json, party_snapshot_json,
                                render_model_json, status, created_by, version
                            ) VALUES (
                                :id, :tenantId, :previewNumber, :profileId, :customerId, :companyId,
                                :templateId, :templateVersionId,
                                TIMESTAMPTZ '2026-07-01 00:00:00+00', TIMESTAMPTZ '2026-08-01 00:00:00+00',
                                DATE '2026-07-31', DATE '2026-08-07', 'Asia/Shanghai', 'zh-CN', 'CNY',
                                '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, 'DRAFT', :createdBy, 0
                            )
                            """)
                    .param("id", previewId).param("tenantId", tenantId)
                    .param("previewNumber", "PRE_" + compact(previewId))
                    .param("profileId", UUID.randomUUID()).param("customerId", UUID.randomUUID())
                    .param("companyId", UUID.randomUUID()).param("templateId", UUID.randomUUID())
                    .param("templateVersionId", UUID.randomUUID()).param("createdBy", createdBy).update();
            connection.commit();
        }
        return previewId;
    }

    private UUID fileOwner(UUID fileId) {
        return jdbc.sql("SELECT created_by FROM files WHERE id = :id")
                .param("id", fileId).query(UUID.class).single();
    }

    private Authentication authentication(AuthenticatedUser actor) {
        return UsernamePasswordAuthenticationToken.authenticated(actor, null, actor.getAuthorities());
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("X-Request-Id", UUID.randomUUID().toString());
        return request;
    }

    private <T> T inTransaction(Supplier<T> command) {
        return transactions.execute(status -> command.get());
    }

    private void assertResourceNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOf(DomainException.class)
                .satisfies(exception -> {
                    DomainException domain = (DomainException) exception;
                    assertThat(domain.code()).isEqualTo("RESOURCE_NOT_FOUND");
                    assertThat(domain.status()).isEqualTo(404);
                });
    }

    private String compact(UUID value) {
        return value.toString().replace("-", "");
    }

    private record Fixture(UUID tenantId, AuthenticatedUser victim, AuthenticatedUser attacker,
                           AuthenticatedUser administrator) {
    }
}
