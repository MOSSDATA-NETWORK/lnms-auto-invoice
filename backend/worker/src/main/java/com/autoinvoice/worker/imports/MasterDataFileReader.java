package com.autoinvoice.worker.imports;

import com.autoinvoice.platform.DomainException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class MasterDataFileReader {
    private static final int MAX_ROWS = 100_000;
    private static final int MAX_COLUMNS = 100;
    private static final int MAX_HEADER_CHARACTERS = 256;
    private static final int MAX_CELL_CHARACTERS = 10_000;
    private static final int MAX_SOURCE_BYTES = 25 * 1024 * 1024;
    private static final long MAX_ARCHIVE_ENTRY_BYTES = 64L * 1024 * 1024;
    private static final long MAX_ARCHIVE_TOTAL_BYTES = 128L * 1024 * 1024;
    private static final long MAX_ARCHIVE_TEXT_CHARACTERS = 10L * 1024 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 2_000;

    static {
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(MAX_ARCHIVE_ENTRY_BYTES);
        ZipSecureFile.setMaxTextSize(MAX_ARCHIVE_TEXT_CHARACTERS);
        ZipSecureFile.setMaxFileCount(MAX_ARCHIVE_ENTRIES);
    }

    public List<RowData> read(String filename, String mimeType, byte[] bytes) throws Exception {
        if (bytes.length == 0 || bytes.length > MAX_SOURCE_BYTES) {
            throw new DomainException("IMPORT_LIMIT_EXCEEDED",
                    "Import source must contain between 1 byte and 25 MiB", 422,
                    Map.of("maximum_bytes", MAX_SOURCE_BYTES));
        }
        if ("text/csv".equals(mimeType) || filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            return readCsv(bytes);
        }
        if ("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(mimeType)
                || filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            return readXlsx(bytes);
        }
        throw new DomainException("IMPORT_FILE_TYPE_UNSUPPORTED", "Import source must be CSV or XLSX", 422, Map.of());
    }

    private List<RowData> readCsv(byte[] bytes) throws Exception {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .get();
        try (InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {
            List<String> headers = normalizeHeaders(parser.getHeaderNames());
            List<RowData> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                if (record.getRecordNumber() > MAX_ROWS) {
                    throw tooLarge();
                }
                if (record.size() > MAX_COLUMNS) {
                    throw tooLarge();
                }
                for (int index = 0; index < record.size(); index++) {
                    validateCellLength(record.get(index).trim(),
                            Math.toIntExact(record.getRecordNumber() + 1), index + 1, false);
                }
                LinkedHashMap<String, String> values = new LinkedHashMap<>();
                for (int index = 0; index < headers.size(); index++) {
                    String value = index < record.size() ? record.get(index).trim() : "";
                    values.put(headers.get(index), value);
                }
                if (values.values().stream().anyMatch(value -> !value.isBlank())) {
                    rows.add(new RowData(Math.toIntExact(record.getRecordNumber() + 1), values));
                }
            }
            return List.copyOf(rows);
        }
    }

    private List<RowData> readXlsx(byte[] bytes) throws Exception {
        validateZipArchive(bytes, MAX_ARCHIVE_ENTRY_BYTES, MAX_ARCHIVE_TOTAL_BYTES, MAX_ARCHIVE_ENTRIES);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) {
                return List.of();
            }
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                return List.of();
            }
            int columns = headerRow.getLastCellNum();
            if (columns <= 0 || columns > MAX_COLUMNS) {
                throw tooLarge();
            }
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<String> rawHeaders = new ArrayList<>();
            for (int column = 0; column < columns; column++) {
                Cell cell = headerRow.getCell(column);
                if (cell != null && cell.getCellType() == CellType.FORMULA) {
                    throw new DomainException("IMPORT_FORMULA_UNSUPPORTED",
                            "Spreadsheet formulas are not allowed in master data imports", 422,
                            Map.of("row", headerRow.getRowNum() + 1, "column", column + 1));
                }
                String value = formatter.formatCellValue(cell).trim();
                validateCellLength(value, headerRow.getRowNum() + 1, column + 1, true);
                rawHeaders.add(value);
            }
            List<String> headers = normalizeHeaders(rawHeaders);
            if ((long) sheet.getLastRowNum() - headerRow.getRowNum() > MAX_ROWS) {
                throw tooLarge();
            }
            List<RowData> rows = new ArrayList<>();
            for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row source = sheet.getRow(rowIndex);
                if (source == null) {
                    continue;
                }
                if (source.getLastCellNum() > MAX_COLUMNS) {
                    throw tooLarge();
                }
                for (Cell cell : source) {
                    if (cell.getCellType() == CellType.FORMULA) {
                        throw new DomainException("IMPORT_FORMULA_UNSUPPORTED",
                                "Spreadsheet formulas are not allowed in master data imports", 422,
                                Map.of("row", rowIndex + 1, "column", cell.getColumnIndex() + 1));
                    }
                    validateCellLength(formatter.formatCellValue(cell).trim(), rowIndex + 1,
                            cell.getColumnIndex() + 1, false);
                }
                LinkedHashMap<String, String> values = new LinkedHashMap<>();
                for (int column = 0; column < headers.size(); column++) {
                    Cell cell = source.getCell(column);
                    values.put(headers.get(column), cell == null ? "" : formatter.formatCellValue(cell).trim());
                }
                if (values.values().stream().anyMatch(value -> !value.isBlank())) {
                    rows.add(new RowData(rowIndex + 1, values));
                }
            }
            return List.copyOf(rows);
        } catch (DomainException exception) {
            throw exception;
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? "" : exception.getMessage();
            if (message.contains("Zip bomb detected") || message.contains("maximum size")
                    || message.contains("file count")) {
                throw archiveLimitExceeded();
            }
            throw new DomainException("IMPORT_ARCHIVE_INVALID",
                    "XLSX import source is not a valid Office Open XML archive", 422, Map.of());
        }
    }

    static void validateZipArchive(byte[] bytes, long maximumEntryBytes, long maximumTotalBytes,
                                   int maximumEntries) {
        int entries = 0;
        long totalBytes = 0;
        byte[] buffer = new byte[16 * 1024];
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                entries++;
                if (entries > maximumEntries) {
                    throw archiveLimitExceeded();
                }
                long entryBytes = 0;
                int read;
                while ((read = archive.read(buffer)) != -1) {
                    entryBytes += read;
                    totalBytes += read;
                    if (entryBytes > maximumEntryBytes || totalBytes > maximumTotalBytes) {
                        throw archiveLimitExceeded();
                    }
                }
                archive.closeEntry();
            }
        } catch (DomainException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new DomainException("IMPORT_ARCHIVE_INVALID",
                    "XLSX import source is not a valid ZIP archive", 422, Map.of());
        }
        if (entries == 0) {
            throw new DomainException("IMPORT_ARCHIVE_INVALID",
                    "XLSX import source is not a valid ZIP archive", 422, Map.of());
        }
    }

    private List<String> normalizeHeaders(List<String> source) {
        if (source.isEmpty() || source.size() > MAX_COLUMNS) {
            throw new DomainException("IMPORT_HEADER_INVALID", "Import file must contain 1 to 100 columns", 422,
                    Map.of("column_count", source.size()));
        }
        List<String> headers = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            validateCellLength(source.get(index), 1, index + 1, true);
            headers.add(normalizeHeader(source.get(index)));
        }
        Set<String> unique = new HashSet<>();
        for (String header : headers) {
            if (header.isBlank() || !unique.add(header)) {
                throw new DomainException("IMPORT_HEADER_INVALID",
                        "Import headers must be non-empty and unique after normalization", 422,
                        Map.of("header", header));
            }
        }
        return headers;
    }

    private void validateCellLength(String value, int row, int column, boolean header) {
        int maximum = header ? MAX_HEADER_CHARACTERS : MAX_CELL_CHARACTERS;
        if (value.length() > maximum) {
            throw new DomainException("IMPORT_CELL_TOO_LARGE",
                    header ? "Import header exceeds the 256 character limit"
                            : "Import cell exceeds the 10,000 character limit",
                    422, Map.of("row", row, "column", column, "maximum_characters", maximum));
        }
    }

    private String normalizeHeader(String value) {
        String normalized = value.replace("\ufeff", "").trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized;
    }

    private DomainException tooLarge() {
        return new DomainException("IMPORT_LIMIT_EXCEEDED",
                "Import exceeds the 100,000 row or 100 column limit", 422, Map.of());
    }

    private static DomainException archiveLimitExceeded() {
        return new DomainException("IMPORT_ARCHIVE_LIMIT_EXCEEDED",
                "XLSX archive exceeds the decompression safety limit", 422, Map.of());
    }

    public record RowData(int rowNumber, LinkedHashMap<String, String> values) {
    }
}
