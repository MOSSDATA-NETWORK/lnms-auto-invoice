package com.autoinvoice.worker.imports;

import com.autoinvoice.platform.DomainException;
import com.autoinvoice.platform.UuidV7;
import com.autoinvoice.platform.jobs.BackgroundJob;
import com.autoinvoice.worker.jobs.JobHandler;
import com.autoinvoice.worker.storage.ObjectStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class ImportValidateHandler implements JobHandler {
    public static final String TYPE = "IMPORT_VALIDATE";
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final ObjectStorage objectStorage;
    private final MasterDataFileReader reader;
    private final MasterDataImportSupport support;

    public ImportValidateHandler(JdbcClient jdbc, ObjectMapper objectMapper, ObjectStorage objectStorage,
                                 MasterDataFileReader reader, MasterDataImportSupport support) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.objectStorage = objectStorage;
        this.reader = reader;
        this.support = support;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public JsonNode handle(BackgroundJob job) throws Exception {
        UUID importId = parseImportId(job.payload());
        ImportSource source = load(job.tenantId(), importId);
        if ("SUCCESS".equals(source.status())) {
            return currentResult(source.tenantId(), source.id(), true);
        }
        if ("CANCELLED".equals(source.status())) {
            throw new DomainException("IMPORT_CANCELLED", "Import job was cancelled", 409, Map.of("import_id", importId));
        }
        begin(source);
        byte[] bytes = objectStorage.get(source.bucket(), source.objectKey());
        verifyHash(source, bytes);

        List<MasterDataFileReader.RowData> rows;
        try {
            rows = reader.read(source.filename(), source.mimeType(), bytes);
        } catch (Exception exception) {
            return failInvalidFile(source, exception);
        }

        List<ErrorEntry> allErrors = new ArrayList<>();
        int valid = 0;
        int invalid = 0;
        if (rows.isEmpty()) {
            allErrors.add(new ErrorEntry(1, null, "IMPORT_EMPTY", "Import file contains no data rows", Map.of()));
            invalid = 1;
        } else {
            Set<String> missingHeaders = new HashSet<>(support.requiredHeaders(source.importType()));
            missingHeaders.removeAll(rows.getFirst().values().keySet());
            if (!missingHeaders.isEmpty()) {
                for (String field : missingHeaders) {
                    allErrors.add(new ErrorEntry(1, field, "HEADER_REQUIRED",
                            "Required header is missing", Map.of("missing_header", field)));
                }
                invalid = rows.size();
            } else {
                MasterDataImportSupport.Context context = support.loadContext(source.tenantId());
                Set<String> rowHashes = new HashSet<>();
                for (MasterDataFileReader.RowData row : rows) {
                    JsonNode rowJson = objectMapper.valueToTree(row.values());
                    String rowHash = rowHash(source.importType(), rowJson);
                    if (!rowHashes.add(rowHash)) {
                        invalid++;
                        allErrors.add(new ErrorEntry(row.rowNumber(), null, "DUPLICATE_ROW",
                                "The same normalized row appears more than once in this file", row.values()));
                        continue;
                    }
                    List<MasterDataImportSupport.RowError> errors = support.validate(
                            source.importType(), row.values(), context);
                    String status = errors.isEmpty() ? "VALID" : "INVALID";
                    insertStaging(source, row, rowJson, rowHash, status);
                    if (errors.isEmpty()) {
                        valid++;
                    } else {
                        invalid++;
                        for (MasterDataImportSupport.RowError error : errors) {
                            allErrors.add(new ErrorEntry(row.rowNumber(), error.field(), error.code(),
                                    error.message(), row.values()));
                        }
                    }
                }
            }
        }
        persistErrors(source, allErrors);
        UUID errorFileId = allErrors.isEmpty() ? null : persistErrorFile(source, allErrors);
        jdbc.sql("""
                        UPDATE import_jobs
                        SET status = 'READY', total_rows = :total, valid_rows = :valid,
                            invalid_rows = :invalid, imported_rows = 0, error_file_id = :errorFileId,
                            completed_at = now(), updated_at = now()
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("total", rows.size()).param("valid", valid).param("invalid", invalid)
                .param("errorFileId", errorFileId).param("tenantId", source.tenantId()).param("id", source.id()).update();
        return result(source.id(), "READY", rows.size(), valid, invalid, errorFileId, false);
    }

    private void begin(ImportSource source) {
        jdbc.sql("DELETE FROM import_row_errors WHERE tenant_id = :tenantId AND import_job_id = :id")
                .param("tenantId", source.tenantId()).param("id", source.id()).update();
        jdbc.sql("DELETE FROM import_staging_rows WHERE tenant_id = :tenantId AND import_job_id = :id")
                .param("tenantId", source.tenantId()).param("id", source.id()).update();
        jdbc.sql("""
                        UPDATE import_jobs
                        SET status = 'VALIDATING', total_rows = 0, valid_rows = 0, invalid_rows = 0,
                            imported_rows = 0, error_file_id = NULL, started_at = COALESCE(started_at, now()),
                            completed_at = NULL, updated_at = now()
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", source.tenantId()).param("id", source.id()).update();
    }

    private void insertStaging(ImportSource source, MasterDataFileReader.RowData row, JsonNode rowJson,
                               String rowHash, String status) {
        jdbc.sql("""
                        INSERT INTO import_staging_rows(
                            id, tenant_id, import_job_id, row_number, entity_type,
                            row_data_json, row_hash, status
                        ) VALUES (
                            :id, :tenantId, :importId, :rowNumber, :entityType,
                            CAST(:rowData AS jsonb), :rowHash, :status
                        )
                        """)
                .param("id", UuidV7.generate()).param("tenantId", source.tenantId())
                .param("importId", source.id()).param("rowNumber", row.rowNumber())
                .param("entityType", source.importType()).param("rowData", rowJson.toString())
                .param("rowHash", rowHash).param("status", status).update();
    }

    private void persistErrors(ImportSource source, List<ErrorEntry> errors) {
        for (ErrorEntry error : errors) {
            JsonNode rowData = objectMapper.valueToTree(error.rowData());
            jdbc.sql("""
                            INSERT INTO import_row_errors(
                                id, tenant_id, import_job_id, row_number, field_name,
                                error_code, error_message, row_data_json
                            ) VALUES (
                                :id, :tenantId, :importId, :rowNumber, :field,
                                :code, :message, CAST(:rowData AS jsonb)
                            )
                            """)
                    .param("id", UuidV7.generate()).param("tenantId", source.tenantId())
                    .param("importId", source.id()).param("rowNumber", Math.max(1, error.rowNumber()))
                    .param("field", error.field()).param("code", error.code()).param("message", error.message())
                    .param("rowData", rowData.toString()).update();
        }
    }

    private UUID persistErrorFile(ImportSource source, List<ErrorEntry> errors) throws Exception {
        StringWriter writer = new StringWriter();
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader("row_number", "field_name", "error_code", "error_message", "row_data_json")
                .get();
        try (CSVPrinter printer = format.print(writer)) {
            for (ErrorEntry error : errors) {
                printer.printRecord(error.rowNumber(), error.field(), error.code(), error.message(),
                        objectMapper.writeValueAsString(error.rowData()));
            }
        }
        byte[] bytes = writer.toString().getBytes(StandardCharsets.UTF_8);
        String hash = sha256(bytes);
        String objectKey = source.tenantId() + "/imports/" + source.id() + "/errors/" + hash + ".csv";
        ObjectStorage.StoredObject stored = objectStorage.put(objectKey, bytes, "text/csv");
        UUID candidate = UuidV7.generate();
        jdbc.sql("""
                        INSERT INTO files(
                            id, tenant_id, storage_provider, bucket_name, object_key,
                            original_filename, mime_type, file_size, sha256, created_by
                        ) VALUES (
                            :id, :tenantId, :provider, :bucket, :objectKey,
                            :filename, 'text/csv', :size, :sha256, :createdBy
                        ) ON CONFLICT (tenant_id, bucket_name, object_key) DO NOTHING
                        """)
                .param("id", candidate).param("tenantId", source.tenantId()).param("provider", stored.provider())
                .param("bucket", stored.bucket()).param("objectKey", stored.objectKey())
                .param("filename", "import-" + source.id() + "-errors.csv").param("size", bytes.length)
                .param("sha256", hash).param("createdBy", source.requestedBy()).update();
        return jdbc.sql("""
                        SELECT id FROM files
                        WHERE tenant_id = :tenantId AND bucket_name = :bucket AND object_key = :objectKey
                        """)
                .param("tenantId", source.tenantId()).param("bucket", stored.bucket())
                .param("objectKey", stored.objectKey()).query(UUID.class).single();
    }

    private JsonNode failInvalidFile(ImportSource source, Exception exception) throws Exception {
        String code = exception instanceof DomainException domain ? domain.code() : "IMPORT_FILE_INVALID";
        String message = exception.getMessage() == null ? "Import file cannot be parsed" : exception.getMessage();
        ErrorEntry error = new ErrorEntry(1, null, code, truncate(message, 1000), Map.of());
        persistErrors(source, List.of(error));
        UUID errorFileId = persistErrorFile(source, List.of(error));
        jdbc.sql("""
                        UPDATE import_jobs
                        SET status = 'FAILED', total_rows = 0, valid_rows = 0, invalid_rows = 1,
                            error_file_id = :errorFileId, completed_at = now(), updated_at = now()
                        WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("errorFileId", errorFileId).param("tenantId", source.tenantId()).param("id", source.id()).update();
        return result(source.id(), "FAILED", 0, 0, 1, errorFileId, false);
    }

    private ImportSource load(UUID tenantId, UUID importId) {
        return jdbc.sql("""
                        SELECT job.id, job.tenant_id, job.import_type, job.status, job.requested_by,
                               file.bucket_name, file.object_key, file.original_filename,
                               file.mime_type, file.file_size, file.sha256
                        FROM import_jobs job
                        JOIN files file ON file.tenant_id = job.tenant_id AND file.id = job.source_file_id
                        WHERE job.tenant_id = :tenantId AND job.id = :id
                        """)
                .param("tenantId", tenantId).param("id", importId).query(this::mapSource).optional()
                .orElseThrow(() -> new DomainException("RESOURCE_NOT_FOUND", "Import job was not found", 404,
                        Map.of("import_id", importId)));
    }

    private ImportSource mapSource(ResultSet rs, int row) throws SQLException {
        return new ImportSource(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("import_type"), rs.getString("status"), rs.getObject("requested_by", UUID.class),
                rs.getString("bucket_name"), rs.getString("object_key"), rs.getString("original_filename"),
                rs.getString("mime_type"), rs.getLong("file_size"), rs.getString("sha256"));
    }

    private void verifyHash(ImportSource source, byte[] bytes) {
        if (bytes.length != source.fileSize() || !MessageDigest.isEqual(
                sha256(bytes).getBytes(StandardCharsets.US_ASCII),
                source.sha256().getBytes(StandardCharsets.US_ASCII))) {
            throw new DomainException("FILE_INTEGRITY_FAILED", "Import source file failed its integrity check", 409,
                    Map.of("import_id", source.id()));
        }
    }

    private JsonNode currentResult(UUID tenantId, UUID importId, boolean recovered) {
        return jdbc.sql("""
                        SELECT status, total_rows, valid_rows, invalid_rows, error_file_id
                        FROM import_jobs WHERE tenant_id = :tenantId AND id = :id
                        """)
                .param("tenantId", tenantId).param("id", importId)
                .query((rs, row) -> result(importId, rs.getString("status"), rs.getInt("total_rows"),
                        rs.getInt("valid_rows"), rs.getInt("invalid_rows"),
                        rs.getObject("error_file_id", UUID.class), recovered)).single();
    }

    private ObjectNode result(UUID importId, String status, int total, int valid, int invalid,
                              UUID errorFileId, boolean recovered) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("import_id", importId.toString());
        result.put("status", status);
        result.put("total_rows", total);
        result.put("valid_rows", valid);
        result.put("invalid_rows", invalid);
        if (errorFileId != null) {
            result.put("error_file_id", errorFileId.toString());
        }
        result.put("recovered", recovered);
        return result;
    }

    private UUID parseImportId(JsonNode payload) {
        try {
            return UUID.fromString(payload.path("import_id").asText());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("IMPORT_VALIDATE payload requires import_id", exception);
        }
    }

    private String rowHash(String importType, JsonNode row) {
        try {
            return sha256((importType + "\n" + objectMapper.writeValueAsString(row))
                    .getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Import row cannot be serialized", exception);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String truncate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private record ImportSource(UUID id, UUID tenantId, String importType, String status, UUID requestedBy,
                                String bucket, String objectKey, String filename, String mimeType,
                                long fileSize, String sha256) {
    }

    private record ErrorEntry(int rowNumber, String field, String code, String message, Object rowData) {
    }
}
