package com.autoinvoice.api.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class TenantRelationshipIntegrityIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.9-alpine");

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
    }

    @Test
    void everyTenantScopedSingleColumnForeignKeyHasTheExactCompositeCompanion() {
        assertThat(jdbc.sql("""
                        WITH single_tenant_foreign_keys AS (
                            SELECT foreign_key.*,
                                   child_tenant.attnum AS child_tenant_attnum,
                                   parent_tenant.attnum AS parent_tenant_attnum
                            FROM pg_constraint foreign_key
                            JOIN pg_class child_table ON child_table.oid = foreign_key.conrelid
                            JOIN pg_namespace child_schema ON child_schema.oid = child_table.relnamespace
                            JOIN pg_attribute child_tenant
                              ON child_tenant.attrelid = child_table.oid
                             AND child_tenant.attname = 'tenant_id'
                             AND NOT child_tenant.attisdropped
                            JOIN pg_class parent_table ON parent_table.oid = foreign_key.confrelid
                            JOIN pg_namespace parent_schema ON parent_schema.oid = parent_table.relnamespace
                            JOIN pg_attribute parent_tenant
                              ON parent_tenant.attrelid = parent_table.oid
                             AND parent_tenant.attname = 'tenant_id'
                             AND NOT parent_tenant.attisdropped
                            WHERE foreign_key.contype = 'f'
                              AND child_schema.nspname = 'public'
                              AND parent_schema.nspname = 'public'
                              AND cardinality(foreign_key.conkey) = 1
                              AND foreign_key.conkey[1] <> child_tenant.attnum
                        )
                        SELECT count(*)
                        FROM single_tenant_foreign_keys original
                        WHERE NOT EXISTS (
                            SELECT 1
                            FROM pg_constraint companion
                            WHERE companion.contype = 'f'
                              AND companion.convalidated
                              AND companion.conrelid = original.conrelid
                              AND companion.confrelid = original.confrelid
                              AND companion.conkey = ARRAY[
                                  original.child_tenant_attnum, original.conkey[1]
                              ]::smallint[]
                              AND companion.confkey = ARRAY[
                                  original.parent_tenant_attnum, original.confkey[1]
                              ]::smallint[]
                              AND companion.confupdtype = original.confupdtype
                              AND companion.confdeltype = original.confdeltype
                              AND companion.confmatchtype = original.confmatchtype
                              AND companion.condeferrable = original.condeferrable
                              AND companion.condeferred = original.condeferred
                        )
                        """).query(Integer.class).single()).isZero();

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM pg_constraint
                        WHERE contype = 'f' AND convalidated
                          AND conname LIKE 'fk!_tenant!_%' ESCAPE '!'
                        """).query(Integer.class).single()).isEqualTo(130);
        assertThat(jdbc.sql("""
                        SELECT count(*)
                        FROM (
                            SELECT conrelid, conkey, confrelid, confkey
                            FROM pg_constraint
                            WHERE contype = 'f' AND convalidated
                              AND conname LIKE 'fk!_tenant!_%' ESCAPE '!'
                            GROUP BY conrelid, conkey, confrelid, confkey
                            HAVING count(*) > 1
                        ) duplicate_mapping
                        """).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT pg_get_constraintdef(oid)
                        FROM pg_constraint
                        WHERE conname = 'fk_tenant_usage_sync_runs_job_id_7ac31331'
                        """).query(String.class).single())
                .contains("FOREIGN KEY (tenant_id, job_id)")
                .contains("REFERENCES background_jobs(tenant_id, id)")
                .contains("ON DELETE SET NULL (job_id)");
    }

    @Test
    void crossTenantReferencesFailWhileCascadeAndSetNullStayInsideTheTenant() throws Exception {
        UUID firstTenant = UUID.randomUUID();
        UUID secondTenant = UUID.randomUUID();
        UUID firstUser = UUID.randomUUID();
        UUID firstIdentity = UUID.randomUUID();
        insertTenant(firstTenant, "first");
        insertTenant(secondTenant, "second");
        insertUser(firstTenant, firstUser, "first-user");

        assertForeignKeyViolation("""
                INSERT INTO external_identities(
                    id, tenant_id, user_id, provider_code, subject
                ) VALUES (?, ?, ?, 'OIDC', 'cross-tenant-user')
                """, UUID.randomUUID(), secondTenant, firstUser);

        jdbc.sql("""
                        INSERT INTO external_identities(
                            id, tenant_id, user_id, provider_code, subject
                        ) VALUES (:id, :tenantId, :userId, 'OIDC', 'same-tenant-user')
                        """)
                .param("id", firstIdentity).param("tenantId", firstTenant).param("userId", firstUser).update();
        jdbc.sql("DELETE FROM users WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", firstTenant).param("id", firstUser).update();
        assertThat(jdbc.sql("SELECT count(*) FROM external_identities WHERE id = :id")
                .param("id", firstIdentity).query(Integer.class).single()).isZero();

        UUID firstInstance = insertLibrenmsInstance(firstTenant, "first-instance");
        UUID firstJob = insertBackgroundJob(firstTenant, "first-job");
        UUID secondJob = insertBackgroundJob(secondTenant, "second-job");
        assertForeignKeyViolation("""
                INSERT INTO usage_sync_runs(
                    id, tenant_id, librenms_instance_id, job_id, sync_type, status
                ) VALUES (?, ?, ?, ?, 'CURRENT', 'RUNNING')
                """, UUID.randomUUID(), firstTenant, firstInstance, secondJob);

        UUID runId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO usage_sync_runs(
                            id, tenant_id, librenms_instance_id, job_id, sync_type, status
                        ) VALUES (:id, :tenantId, :instanceId, :jobId, 'CURRENT', 'RUNNING')
                        """)
                .param("id", runId).param("tenantId", firstTenant)
                .param("instanceId", firstInstance).param("jobId", firstJob).update();
        jdbc.sql("DELETE FROM background_jobs WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", firstTenant).param("id", firstJob).update();
        assertThat(jdbc.sql("SELECT tenant_id FROM usage_sync_runs WHERE id = :id")
                .param("id", runId).query(UUID.class).single()).isEqualTo(firstTenant);
        assertThat(jdbc.sql("SELECT job_id IS NULL FROM usage_sync_runs WHERE id = :id")
                .param("id", runId).query(Boolean.class).single()).isTrue();
    }

    private static void assertForeignKeyViolation(String sql, Object... parameters) throws Exception {
        assertThatThrownBy(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int index = 0; index < parameters.length; index++) {
                    statement.setObject(index + 1, parameters[index]);
                }
                statement.executeUpdate();
            }
        }).isInstanceOf(SQLException.class)
                .satisfies(exception -> assertThat(((SQLException) exception).getSQLState()).isEqualTo("23503"));
    }

    private static void insertTenant(UUID tenantId, String label) {
        jdbc.sql("INSERT INTO tenants(id, tenant_code, tenant_name) VALUES (:id, :code, :name)")
                .param("id", tenantId).param("code", "tenant-" + label + "-" + compact(tenantId))
                .param("name", "Tenant " + label).update();
    }

    private static void insertUser(UUID tenantId, UUID userId, String label) {
        jdbc.sql("""
                        INSERT INTO users(id, tenant_id, username, email, display_name)
                        VALUES (:id, :tenantId, :username, :email, :displayName)
                        """)
                .param("id", userId).param("tenantId", tenantId)
                .param("username", label).param("email", label + "@example.invalid")
                .param("displayName", label).update();
    }

    private static UUID insertLibrenmsInstance(UUID tenantId, String label) {
        UUID instanceId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO librenms_instances(
                            id, tenant_id, instance_code, instance_name, base_url,
                            api_token_ciphertext, timezone
                        ) VALUES (
                            :id, :tenantId, :code, :name,
                            'https://librenms.example.invalid', 'ciphertext', 'UTC'
                        )
                        """)
                .param("id", instanceId).param("tenantId", tenantId)
                .param("code", label + "-" + compact(instanceId)).param("name", label).update();
        return instanceId;
    }

    private static UUID insertBackgroundJob(UUID tenantId, String label) {
        UUID jobId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO background_jobs(id, tenant_id, job_type, unique_key, payload_json)
                        VALUES (:id, :tenantId, 'TENANT_TEST', :key, '{}'::jsonb)
                        """)
                .param("id", jobId).param("tenantId", tenantId)
                .param("key", label + "-" + compact(jobId)).update();
        return jobId;
    }

    private static String compact(UUID id) {
        return id.toString().replace("-", "").substring(0, 12);
    }
}
