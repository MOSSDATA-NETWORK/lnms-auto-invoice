package com.autoinvoice.worker.imports;

import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.audit.AuditService;
import com.autoinvoice.platform.jobs.BackgroundJob;
import com.autoinvoice.worker.jobs.JobHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ImportConfirmHandler implements JobHandler {
    public static final String TYPE = "IMPORT_CONFIRM";
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final MasterDataImportSupport support;
    private final AuditService audit;
    private final TransactionTemplate transactions;

    public ImportConfirmHandler(JdbcClient jdbc, ObjectMapper objectMapper,
                                MasterDataImportSupport support, AuditService audit,
                                TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.support = support;
        this.audit = audit;
        this.transactions = transactions;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public JsonNode handle(BackgroundJob job) {
        UUID importId = parseImportId(job.payload());
        ImportState state = loadState(job.tenantId(), importId);
        if (List.of("SUCCESS", "PARTIAL").contains(state.status())) {
            return result(state.id(), state.status(), state.importedRows(), state.invalidRows(), true);
        }
        if (!List.of("READY", "IMPORTING").contains(state.status()) || state.invalidRows() > 0) {
            throw new DomainException("IMPORT_NOT_READY", "Import is not eligible for confirmation", 409,
                    Map.of("import_id", importId, "status", state.status(), "invalid_rows", state.invalidRows()));
        }
        jdbc.sql("""
                        UPDATE import_jobs SET status = 'IMPORTING', started_at = COALESCE(started_at, now()),
                            completed_at = NULL, updated_at = now()
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", state.tenantId()).param("id", state.id()).update();

        List<StagedRow> rows = jdbc.sql("""
                        SELECT id, row_number, row_data_json, status, imported_resource_id
                        FROM import_staging_rows
                        WHERE tenant_id = :tenantId AND import_job_id = :importId
                          AND status IN ('VALID', 'IMPORTED', 'INVALID', 'SKIPPED')
                        ORDER BY row_number
                        """)
                .param("tenantId", state.tenantId()).param("importId", state.id()).query(this::mapRow).list();
        for (StagedRow row : rows) {
            if (!"VALID".equals(row.status())) {
                continue;
            }
            try {
                importAtomically(state, row);
            } catch (RuntimeException exception) {
                recordFailureAtomically(state, row, exception);
            }
        }
        RowCounts counts = loadRowCounts(state);
        if (counts.remaining() > 0) {
            throw new IllegalStateException("Import confirmation left unprocessed staging rows");
        }
        String finalStatus = counts.failed() == 0 ? "SUCCESS" : "PARTIAL";
        ObjectNode result = result(state.id(), finalStatus, counts.imported(), counts.failed(), false);
        transactions.executeWithoutResult(transaction -> {
            int updated = jdbc.sql("""
                            UPDATE import_jobs
                            SET status = :status, imported_rows = :imported, invalid_rows = :failed,
                                completed_at = now(), updated_at = now()
                            WHERE tenant_id = :tenantId AND id = :id AND status = 'IMPORTING'
                            """)
                    .param("status", finalStatus).param("imported", counts.imported())
                    .param("failed", counts.failed()).param("tenantId", state.tenantId())
                    .param("id", state.id()).update();
            if (updated != 1) {
                throw new DomainException("IMPORT_STATE_CHANGED",
                        "Import state changed before confirmation completed", 409,
                        Map.of("import_id", state.id()));
            }
            audit.record(state.tenantId(), "SYSTEM", null, "Import Worker", "import.completed",
                    "import_job", state.id(), null, result,
                    "Persistent import confirmation", job.id().toString());
        });
        return result;
    }

    private void importAtomically(ImportState state, StagedRow row) {
        transactions.executeWithoutResult(transaction -> {
            UUID resourceId = importRow(state, row.values());
            int updated = jdbc.sql("""
                            UPDATE import_staging_rows
                            SET status = 'IMPORTED', imported_resource_id = :resourceId, updated_at = now()
                            WHERE tenant_id = :tenantId AND id = :id AND status = 'VALID'
                            """)
                    .param("resourceId", resourceId).param("tenantId", state.tenantId())
                    .param("id", row.id()).update();
            if (updated == 0 && !"IMPORTED".equals(stagingStatus(state.tenantId(), row.id()))) {
                throw new DomainException("IMPORT_ROW_STATE_CHANGED",
                        "Import staging row changed while it was being confirmed", 409,
                        Map.of("import_id", state.id(), "row_number", row.rowNumber()));
            }
        });
    }

    private void recordFailureAtomically(ImportState state, StagedRow row, RuntimeException exception) {
        transactions.executeWithoutResult(transaction -> {
            int updated = jdbc.sql("""
                            UPDATE import_staging_rows SET status = 'INVALID', updated_at = now()
                            WHERE tenant_id = :tenantId AND id = :id AND status = 'VALID'
                            """)
                    .param("tenantId", state.tenantId()).param("id", row.id()).update();
            String currentStatus = updated == 1 ? "INVALID" : stagingStatus(state.tenantId(), row.id());
            if ("IMPORTED".equals(currentStatus)) {
                return;
            }
            if (!"INVALID".equals(currentStatus)) {
                throw new DomainException("IMPORT_ROW_STATE_CHANGED",
                        "Import staging row changed while its failure was being recorded", 409,
                        Map.of("import_id", state.id(), "row_number", row.rowNumber()));
            }
            jdbc.sql("""
                            DELETE FROM import_row_errors
                            WHERE tenant_id = :tenantId AND import_job_id = :importId
                              AND row_number = :rowNumber AND error_code = 'IMPORT_WRITE_FAILED'
                            """)
                    .param("tenantId", state.tenantId()).param("importId", state.id())
                    .param("rowNumber", row.rowNumber()).update();
            jdbc.sql("""
                            INSERT INTO import_row_errors(
                                id, tenant_id, import_job_id, row_number, error_code,
                                error_message, row_data_json
                            ) VALUES (
                                :id, :tenantId, :importId, :rowNumber, 'IMPORT_WRITE_FAILED',
                                :message, CAST(:rowData AS jsonb)
                            )
                            """)
                    .param("id", UuidV7.generate()).param("tenantId", state.tenantId())
                    .param("importId", state.id()).param("rowNumber", row.rowNumber())
                    .param("message", truncate(exception.getMessage() == null
                            ? "Database rejected the imported row" : exception.getMessage(), 1000))
                    .param("rowData", json(row.values())).update();
        });
    }

    private String stagingStatus(UUID tenantId, UUID rowId) {
        return jdbc.sql("SELECT status FROM import_staging_rows WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", rowId).query(String.class).optional()
                .orElse("MISSING");
    }

    private RowCounts loadRowCounts(ImportState state) {
        return jdbc.sql("""
                        SELECT COUNT(*) FILTER (WHERE status = 'IMPORTED') AS imported,
                               COUNT(*) FILTER (WHERE status IN ('INVALID', 'SKIPPED')) AS failed,
                               COUNT(*) FILTER (WHERE status NOT IN ('IMPORTED', 'INVALID', 'SKIPPED')) AS remaining
                        FROM import_staging_rows
                        WHERE tenant_id = :tenantId AND import_job_id = :importId
                        """)
                .param("tenantId", state.tenantId()).param("importId", state.id())
                .query((rs, row) -> new RowCounts(
                        Math.toIntExact(rs.getLong("imported")), Math.toIntExact(rs.getLong("failed")),
                        Math.toIntExact(rs.getLong("remaining"))))
                .single();
    }

    private UUID importRow(ImportState state, LinkedHashMap<String, String> row) {
        return switch (state.importType()) {
            case "CUSTOMERS" -> importCustomer(state, row);
            case "COMPANIES" -> importCompany(state, row);
            case "SERVICES" -> importService(state, row);
            case "CONTRACTS" -> importContract(state, row);
            case "CONTRACT_ITEMS" -> importContractItem(state, row);
            default -> throw new DomainException("IMPORT_TYPE_UNSUPPORTED", "Import type is unsupported", 422,
                    Map.of("import_type", state.importType()));
        };
    }

    private UUID importCustomer(ImportState state, Map<String, String> row) {
        UUID candidate = UuidV7.generate();
        return jdbc.sql("""
                        WITH inserted AS (
                            INSERT INTO customers(
                                id, tenant_id, customer_no, customer_name, customer_type, owner_user_id,
                                default_currency, default_language, default_billing_cycle,
                                default_payment_terms_days, status, notes
                            ) VALUES (
                                :id, :tenantId, :customerNo, :customerName, :customerType, :owner,
                                :currency, :language, :cycle, :terms, :status, :notes
                            ) ON CONFLICT (tenant_id, customer_no) DO NOTHING RETURNING id
                        )
                        SELECT id FROM inserted
                        UNION ALL SELECT id FROM customers WHERE tenant_id = :tenantId AND customer_no = :customerNo
                        LIMIT 1
                        """)
                .param("id", candidate).param("tenantId", state.tenantId())
                .param("customerNo", support.value(row, "customer_no"))
                .param("customerName", support.value(row, "customer_name"))
                .param("customerType", support.value(row, "customer_type", "ENTERPRISE"))
                .param("owner", state.requestedBy()).param("currency", support.value(row, "default_currency", "CNY"))
                .param("language", support.value(row, "default_language", "zh-CN"))
                .param("cycle", support.value(row, "default_billing_cycle", "MONTHLY"))
                .param("terms", Integer.parseInt(support.value(row, "default_payment_terms_days", "7")))
                .param("status", support.value(row, "status", "ACTIVE")).param("notes", nullable(row, "notes"))
                .query(UUID.class).single();
    }

    private UUID importCompany(ImportState state, Map<String, String> row) {
        UUID customerId = idByCode("customers", "customer_no", state.tenantId(), support.value(row, "customer_no"));
        UUID candidate = UuidV7.generate();
        return jdbc.sql("""
                        WITH inserted AS (
                            INSERT INTO companies(
                                id, tenant_id, customer_id, company_code, company_name, company_name_en,
                                country_region, address, tax_number, invoice_title,
                                default_currency, default_tax_rate, status
                            ) VALUES (
                                :id, :tenantId, :customerId, :code, :name, :nameEn,
                                :country, :address, :taxNumber, :invoiceTitle,
                                :currency, :taxRate, :status
                            ) ON CONFLICT (tenant_id, company_code) DO NOTHING RETURNING id
                        )
                        SELECT id FROM inserted
                        UNION ALL SELECT id FROM companies WHERE tenant_id = :tenantId AND company_code = :code
                        LIMIT 1
                        """)
                .param("id", candidate).param("tenantId", state.tenantId()).param("customerId", customerId)
                .param("code", support.value(row, "company_code")).param("name", support.value(row, "company_name"))
                .param("nameEn", nullable(row, "company_name_en")).param("country", nullable(row, "country_region"))
                .param("address", nullable(row, "address")).param("taxNumber", nullable(row, "tax_number"))
                .param("invoiceTitle", nullable(row, "invoice_title"))
                .param("currency", nullable(row, "default_currency"))
                .param("taxRate", decimalOrNull(row, "default_tax_rate"))
                .param("status", support.value(row, "status", "ACTIVE")).query(UUID.class).single();
    }

    private UUID importService(ImportState state, Map<String, String> row) {
        UUID customerId = idByCode("customers", "customer_no", state.tenantId(), support.value(row, "customer_no"));
        UUID companyId = idByCode("companies", "company_code", state.tenantId(), support.value(row, "company_code"));
        UUID productId = nullableIdByCode("products", "product_code", state.tenantId(), nullable(row, "product_code"));
        UUID candidate = UuidV7.generate();
        return jdbc.sql("""
                        WITH inserted AS (
                            INSERT INTO services(
                                id, tenant_id, service_no, customer_id, company_id, product_id,
                                service_name, service_type, region, datacenter, line_name,
                                activated_on, deactivated_on, status, notes
                            ) VALUES (
                                :id, :tenantId, :serviceNo, :customerId, :companyId, :productId,
                                :name, :type, :region, :datacenter, :lineName,
                                :activatedOn, :deactivatedOn, :status, :notes
                            ) ON CONFLICT (tenant_id, service_no) DO NOTHING RETURNING id
                        )
                        SELECT id FROM inserted
                        UNION ALL SELECT id FROM services WHERE tenant_id = :tenantId AND service_no = :serviceNo
                        LIMIT 1
                        """)
                .param("id", candidate).param("tenantId", state.tenantId())
                .param("serviceNo", support.value(row, "service_no")).param("customerId", customerId)
                .param("companyId", companyId).param("productId", productId)
                .param("name", support.value(row, "service_name")).param("type", support.value(row, "service_type"))
                .param("region", nullable(row, "region")).param("datacenter", nullable(row, "datacenter"))
                .param("lineName", nullable(row, "line_name")).param("activatedOn", dateOrNull(row, "activated_on"))
                .param("deactivatedOn", dateOrNull(row, "deactivated_on"))
                .param("status", support.value(row, "status", "PENDING")).param("notes", nullable(row, "notes"))
                .query(UUID.class).single();
    }

    private UUID importContract(ImportState state, Map<String, String> row) {
        UUID customerId = idByCode("customers", "customer_no", state.tenantId(), support.value(row, "customer_no"));
        UUID companyId = idByCode("companies", "company_code", state.tenantId(), support.value(row, "company_code"));
        UUID candidate = UuidV7.generate();
        return jdbc.sql("""
                        WITH inserted AS (
                            INSERT INTO contracts(
                                id, tenant_id, contract_no, customer_id, company_id, contract_name,
                                effective_from, effective_to, auto_renew, billing_cycle, billing_day,
                                payment_terms_days, currency_code, tax_rate, tax_inclusive, status, notes
                            ) VALUES (
                                :id, :tenantId, :contractNo, :customerId, :companyId, :name,
                                :effectiveFrom, :effectiveTo, :autoRenew, :cycle, :billingDay,
                                :terms, :currency, :taxRate, :taxInclusive, :status, :notes
                            ) ON CONFLICT (tenant_id, contract_no) DO NOTHING RETURNING id
                        )
                        SELECT id FROM inserted
                        UNION ALL SELECT id FROM contracts WHERE tenant_id = :tenantId AND contract_no = :contractNo
                        LIMIT 1
                        """)
                .param("id", candidate).param("tenantId", state.tenantId())
                .param("contractNo", support.value(row, "contract_no")).param("customerId", customerId)
                .param("companyId", companyId).param("name", support.value(row, "contract_name"))
                .param("effectiveFrom", LocalDate.parse(support.value(row, "effective_from")))
                .param("effectiveTo", dateOrNull(row, "effective_to"))
                .param("autoRenew", support.booleanValue(row, "auto_renew", false))
                .param("cycle", support.value(row, "billing_cycle", "MONTHLY"))
                .param("billingDay", integerOrNull(row, "billing_day"))
                .param("terms", Integer.parseInt(support.value(row, "payment_terms_days", "7")))
                .param("currency", support.value(row, "currency_code")).param("taxRate", decimalOrNull(row, "tax_rate"))
                .param("taxInclusive", support.booleanValue(row, "tax_inclusive", false))
                .param("status", support.value(row, "status", "DRAFT")).param("notes", nullable(row, "notes"))
                .query(UUID.class).single();
    }

    private UUID importContractItem(ImportState state, Map<String, String> row) {
        UUID contractId = idByCode("contracts", "contract_no", state.tenantId(), support.value(row, "contract_no"));
        UUID serviceId = idByCode("services", "service_no", state.tenantId(), support.value(row, "service_no"));
        UUID pricingRuleId = idByCode("pricing_rules", "rule_code", state.tenantId(),
                support.value(row, "pricing_rule_code"));
        UUID candidate = UuidV7.generate();
        return jdbc.sql("""
                        WITH inserted AS (
                            INSERT INTO contract_items(
                                id, tenant_id, contract_item_no, contract_id, service_id, pricing_rule_id,
                                item_name, billing_type, billing_cycle, effective_from, effective_to,
                                default_quantity, unit, tax_category, auto_bill, visible_on_invoice,
                                sort_order, status
                            ) VALUES (
                                :id, :tenantId, :itemNo, :contractId, :serviceId, :pricingRuleId,
                                :name, :billingType, :cycle, :effectiveFrom, :effectiveTo,
                                :quantity, :unit, :taxCategory, :autoBill, :visible,
                                :sortOrder, :status
                            ) ON CONFLICT (tenant_id, contract_item_no) DO NOTHING RETURNING id
                        )
                        SELECT id FROM inserted
                        UNION ALL SELECT id FROM contract_items WHERE tenant_id = :tenantId AND contract_item_no = :itemNo
                        LIMIT 1
                        """)
                .param("id", candidate).param("tenantId", state.tenantId())
                .param("itemNo", support.value(row, "contract_item_no")).param("contractId", contractId)
                .param("serviceId", serviceId).param("pricingRuleId", pricingRuleId)
                .param("name", support.value(row, "item_name")).param("billingType", support.value(row, "billing_type"))
                .param("cycle", support.value(row, "billing_cycle", "MONTHLY"))
                .param("effectiveFrom", OffsetDateTime.parse(support.value(row, "effective_from")))
                .param("effectiveTo", offsetDateTimeOrNull(row, "effective_to"))
                .param("quantity", decimalOrNull(row, "default_quantity")).param("unit", nullable(row, "unit"))
                .param("taxCategory", nullable(row, "tax_category"))
                .param("autoBill", support.booleanValue(row, "auto_bill", true))
                .param("visible", support.booleanValue(row, "visible_on_invoice", true))
                .param("sortOrder", Integer.parseInt(support.value(row, "sort_order", "0")))
                .param("status", support.value(row, "status", "DRAFT")).query(UUID.class).single();
    }

    private UUID idByCode(String table, String column, UUID tenantId, String code) {
        if (!List.of("customers", "companies", "contracts", "services", "pricing_rules", "products").contains(table)
                || !List.of("customer_no", "company_code", "contract_no", "service_no", "rule_code", "product_code")
                .contains(column)) {
            throw new IllegalArgumentException("Unsupported import reference lookup");
        }
        return jdbc.sql("SELECT id FROM " + table + " WHERE tenant_id = :tenantId AND " + column + " = :code")
                .param("tenantId", tenantId).param("code", code).query(UUID.class).single();
    }

    private UUID nullableIdByCode(String table, String column, UUID tenantId, String code) {
        return code == null ? null : idByCode(table, column, tenantId, code);
    }

    private String nullable(Map<String, String> row, String field) {
        String value = support.value(row, field);
        return value.isBlank() ? null : value;
    }

    private LocalDate dateOrNull(Map<String, String> row, String field) {
        String value = nullable(row, field);
        return value == null ? null : LocalDate.parse(value);
    }

    private OffsetDateTime offsetDateTimeOrNull(Map<String, String> row, String field) {
        String value = nullable(row, field);
        return value == null ? null : OffsetDateTime.parse(value);
    }

    private BigDecimal decimalOrNull(Map<String, String> row, String field) {
        String value = nullable(row, field);
        return value == null ? null : new BigDecimal(value);
    }

    private Integer integerOrNull(Map<String, String> row, String field) {
        String value = nullable(row, field);
        return value == null ? null : Integer.valueOf(value);
    }

    private ImportState loadState(UUID tenantId, UUID importId) {
        return jdbc.sql("""
                        SELECT id, tenant_id, import_type, status, invalid_rows, imported_rows, requested_by
                        FROM import_jobs WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId).param("id", importId).query((rs, row) -> new ImportState(
                        rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getString("import_type"), rs.getString("status"), rs.getInt("invalid_rows"),
                        rs.getInt("imported_rows"), rs.getObject("requested_by", UUID.class)))
                .optional().orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Import job was not found", 404,
                        Map.of("import_id", importId)));
    }

    private StagedRow mapRow(ResultSet rs, int row) throws SQLException {
        try {
            LinkedHashMap<String, String> values = objectMapper.readValue(
                    rs.getString("row_data_json"), new TypeReference<>() {});
            return new StagedRow(rs.getObject("id", UUID.class), rs.getInt("row_number"), values,
                    rs.getString("status"), rs.getObject("imported_resource_id", UUID.class));
        } catch (JsonProcessingException exception) {
            throw new SQLException("Persisted import staging row is invalid", exception);
        }
    }

    private ObjectNode result(UUID importId, String status, int imported, int failed, boolean recovered) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("import_id", importId.toString());
        result.put("status", status);
        result.put("imported_rows", imported);
        result.put("failed_rows", failed);
        result.put("recovered", recovered);
        return result;
    }

    private UUID parseImportId(JsonNode payload) {
        try {
            return UUID.fromString(payload.path("import_id").asText());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("IMPORT_CONFIRM payload requires import_id", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Import row is not serializable", exception);
        }
    }

    private String truncate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private record ImportState(UUID id, UUID tenantId, String importType, String status,
                               int invalidRows, int importedRows, UUID requestedBy) {
    }

    private record StagedRow(UUID id, int rowNumber, LinkedHashMap<String, String> values,
                              String status, UUID importedResourceId) {
    }

    private record RowCounts(int imported, int failed, int remaining) {
    }
}
