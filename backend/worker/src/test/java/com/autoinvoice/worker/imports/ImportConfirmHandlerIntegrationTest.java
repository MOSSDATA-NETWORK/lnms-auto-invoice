package com.autoinvoice.worker.imports;

import com.autoinvoice.platform.audit.AuditService;
import com.autoinvoice.platform.jobs.BackgroundJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ImportConfirmHandlerIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.9-alpine");

    private static JdbcClient jdbc;
    private static ObjectMapper objectMapper;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void resourceInsertRollsBackWhenTheStagingCheckpointCannotCommit() {
        Fixture fixture = fixture("rollback");
        String suffix = fixture.rowId().toString().replace("-", "");
        jdbc.sql("""
                        CREATE FUNCTION fail_imported_%s() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                          IF NEW.id = '%s'::uuid AND NEW.status = 'IMPORTED' THEN
                            RAISE EXCEPTION 'simulated staging checkpoint failure';
                          END IF;
                          RETURN NEW;
                        END $$
                        """.formatted(suffix, fixture.rowId())).update();
        jdbc.sql("""
                        CREATE TRIGGER fail_imported_%s BEFORE UPDATE ON import_staging_rows
                        FOR EACH ROW EXECUTE FUNCTION fail_imported_%s()
                        """.formatted(suffix, suffix)).update();

        var result = handler().handle(backgroundJob(fixture));

        assertThat(result.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(result.path("failed_rows").asInt()).isEqualTo(1);
        assertThat(count("customers", "tenant_id", fixture.tenantId())).isZero();
        assertThat(stagingStatus(fixture)).isEqualTo("INVALID");
        assertThat(errorCount(fixture)).isEqualTo(1);
        assertThat(jobStatus(fixture)).isEqualTo("PARTIAL");
        assertThat(auditCount(fixture)).isEqualTo(1);

        var recovered = handler().handle(backgroundJob(fixture));
        assertThat(recovered.path("recovered").asBoolean()).isTrue();
        assertThat(errorCount(fixture)).isEqualTo(1);
        assertThat(auditCount(fixture)).isEqualTo(1);
    }

    @Test
    void finalStateAndAuditRollbackTogetherAndRetryCompletesExactlyOnce() {
        Fixture fixture = fixture("audit");
        String suffix = fixture.tenantId().toString().replace("-", "");
        jdbc.sql("""
                        CREATE FUNCTION fail_audit_%s() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                          IF NEW.tenant_id = '%s'::uuid THEN
                            RAISE EXCEPTION 'simulated audit persistence failure';
                          END IF;
                          RETURN NEW;
                        END $$
                        """.formatted(suffix, fixture.tenantId())).update();
        jdbc.sql("""
                        CREATE TRIGGER fail_audit_%s BEFORE INSERT ON audit_logs
                        FOR EACH ROW EXECUTE FUNCTION fail_audit_%s()
                        """.formatted(suffix, suffix)).update();

        assertThatThrownBy(() -> handler().handle(backgroundJob(fixture)))
                .hasMessageContaining("simulated audit persistence failure");
        assertThat(count("customers", "tenant_id", fixture.tenantId())).isEqualTo(1);
        assertThat(stagingStatus(fixture)).isEqualTo("IMPORTED");
        assertThat(jobStatus(fixture)).isEqualTo("IMPORTING");
        assertThat(auditCount(fixture)).isZero();

        jdbc.sql("DROP TRIGGER fail_audit_" + suffix + " ON audit_logs").update();
        jdbc.sql("DROP FUNCTION fail_audit_" + suffix + "()").update();

        var recovered = handler().handle(backgroundJob(fixture));
        assertThat(recovered.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(recovered.path("imported_rows").asInt()).isEqualTo(1);
        assertThat(count("customers", "tenant_id", fixture.tenantId())).isEqualTo(1);
        assertThat(jobStatus(fixture)).isEqualTo("SUCCESS");
        assertThat(auditCount(fixture)).isEqualTo(1);
    }

    private ImportConfirmHandler handler() {
        return new ImportConfirmHandler(jdbc, objectMapper, new MasterDataImportSupport(jdbc),
                new AuditService(jdbc, objectMapper), transactions);
    }

    private Fixture fixture(String label) {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        UUID importId = UUID.randomUUID();
        UUID rowId = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenants(id, tenant_code, tenant_name) VALUES (:id, :code, :name)")
                .param("id", tenantId).param("code", label + "-" + tenantId).param("name", label).update();
        jdbc.sql("""
                        INSERT INTO users(id, tenant_id, username, email, display_name)
                        VALUES (:id, :tenantId, :username, :email, 'Import operator')
                        """)
                .param("id", userId).param("tenantId", tenantId).param("username", label)
                .param("email", label + "@example.test").update();
        jdbc.sql("""
                        INSERT INTO files(id, tenant_id, storage_provider, bucket_name, object_key,
                                          original_filename, mime_type, file_size, sha256, created_by)
                        VALUES (:id, :tenantId, 'MINIO', 'test', :objectKey,
                                'customers.csv', 'text/csv', 1, repeat('0', 64), :userId)
                        """)
                .param("id", fileId).param("tenantId", tenantId)
                .param("objectKey", tenantId + "/uploads/source.csv").param("userId", userId).update();
        jdbc.sql("""
                        INSERT INTO import_jobs(id, tenant_id, import_type, source_file_id, idempotency_key,
                                                status, total_rows, valid_rows, invalid_rows, requested_by)
                        VALUES (:id, :tenantId, 'CUSTOMERS', :fileId, :key, 'READY', 1, 1, 0, :userId)
                        """)
                .param("id", importId).param("tenantId", tenantId).param("fileId", fileId)
                .param("key", "import-" + importId).param("userId", userId).update();
        jdbc.sql("""
                        INSERT INTO import_staging_rows(id, tenant_id, import_job_id, row_number, entity_type,
                                                        row_data_json, row_hash, status)
                        VALUES (:id, :tenantId, :importId, 2, 'CUSTOMERS',
                                CAST(:rowData AS jsonb), repeat('1', 64), 'VALID')
                        """)
                .param("id", rowId).param("tenantId", tenantId).param("importId", importId)
                .param("rowData", "{\"customer_no\":\"CUST-001\",\"customer_name\":\"Acme\"}").update();
        return new Fixture(tenantId, importId, rowId);
    }

    private BackgroundJob backgroundJob(Fixture fixture) {
        return new BackgroundJob(UUID.randomUUID(), fixture.tenantId(), ImportConfirmHandler.TYPE,
                "confirm:" + fixture.importId(), objectMapper.createObjectNode()
                .put("import_id", fixture.importId().toString()), "RUNNING", 1, 3, Instant.now(), null);
    }

    private long count(String table, String column, UUID value) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = :value")
                .param("value", value).query(Long.class).single();
    }

    private String stagingStatus(Fixture fixture) {
        return jdbc.sql("SELECT status FROM import_staging_rows WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", fixture.tenantId()).param("id", fixture.rowId())
                .query(String.class).single();
    }

    private int errorCount(Fixture fixture) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM import_row_errors
                        WHERE tenant_id = :tenantId AND import_job_id = :importId
                        """)
                .param("tenantId", fixture.tenantId()).param("importId", fixture.importId())
                .query(Integer.class).single();
    }

    private int auditCount(Fixture fixture) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM audit_logs
                        WHERE tenant_id = :tenantId AND object_type = 'import_job' AND object_id = :importId
                        """)
                .param("tenantId", fixture.tenantId()).param("importId", fixture.importId())
                .query(Integer.class).single();
    }

    private String jobStatus(Fixture fixture) {
        return jdbc.sql("SELECT status FROM import_jobs WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", fixture.tenantId()).param("id", fixture.importId())
                .query(String.class).single();
    }

    private record Fixture(UUID tenantId, UUID importId, UUID rowId) {
    }
}
