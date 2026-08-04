package com.autoinvoice.api.database;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class ReferenceSchemaIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.9-alpine");

    @Test
    void executesReferenceSchemaAndIncludesLatestInvoiceIntegrityRules() throws Exception {
        Path schemaPath = locateReferenceSchema();
        String schemaSql = Files.readString(schemaPath);

        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.createStatement()) {
            statement.execute(schemaSql);

            assertThat(singleLong(statement, """
                    SELECT count(*)
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND ((table_name = 'invoice_items' AND column_name = 'source_preview_item_id')
                           OR (table_name = 'invoice_adjustments'
                               AND column_name = 'source_preview_adjustment_id'))
                      AND is_nullable = 'NO'
                    """)).isEqualTo(2);
            assertThat(singleLong(statement, """
                    SELECT count(*)
                    FROM pg_trigger
                    WHERE tgname IN (
                        'trg_preview_items_protect_final_snapshot',
                        'trg_preview_adjustments_protect_final_snapshot',
                        'trg_preview_exclusions_protect_final_snapshot',
                        'trg_invoice_items_check_preview_snapshot',
                        'trg_invoice_adjustments_check_preview_snapshot',
                        'trg_invoices_validate_confirmation_and_replacement',
                        'trg_invoice_relations_validate_replaces',
                        'trg_invoices_require_completed_correction'
                    ) AND NOT tgisinternal
                    """)).isEqualTo(8);
            assertThat(singleLong(statement, """
                    SELECT count(*)
                    FROM pg_proc procedure
                    JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
                    WHERE namespace.nspname = 'public'
                      AND procedure.proname IN (
                          'protect_finalizing_preview_child',
                          'validate_formal_invoice_item_insert',
                          'validate_formal_invoice_adjustment_insert',
                          'validate_invoice_confirmation_and_replacement',
                          'validate_replaces_relation_insert',
                          'require_completed_correction_replacement',
                          'refresh_payment_status_from_history'
                      )
                      AND NOT procedure.prosecdef
                      AND ARRAY['search_path=pg_catalog, public, pg_temp']::text[]
                          <@ procedure.proconfig
                    """)).isEqualTo(7);
        }
    }

    private static long singleLong(java.sql.Statement statement, String sql) throws Exception {
        try (var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static Path locateReferenceSchema() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("docs/sql/001-initial-schema.sql");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate docs/sql/001-initial-schema.sql");
    }
}
