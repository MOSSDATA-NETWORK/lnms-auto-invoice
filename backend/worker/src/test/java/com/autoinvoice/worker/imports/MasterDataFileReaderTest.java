package com.autoinvoice.worker.imports;

import com.autoinvoice.platform.DomainException;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MasterDataFileReaderTest {
    private final MasterDataFileReader reader = new MasterDataFileReader();

    @Test
    void readsUtf8CsvAndNormalizesHeaders() throws Exception {
        byte[] csv = ("\ufeffCustomer No,Customer Name,Default Currency\n"
                + "CUST-001,示例客户,CNY\n").getBytes(StandardCharsets.UTF_8);

        var rows = reader.read("customers.csv", "text/csv", csv);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().rowNumber()).isEqualTo(2);
        assertThat(rows.getFirst().values())
                .containsEntry("customer_no", "CUST-001")
                .containsEntry("customer_name", "示例客户")
                .containsEntry("default_currency", "CNY");
    }

    @Test
    void rejectsSpreadsheetFormulas() throws Exception {
        byte[] workbookBytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("customers");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("customer_no");
            header.createCell(1).setCellValue("customer_name");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("CUST-001");
            row.createCell(1, CellType.FORMULA).setCellFormula("CONCAT(\"bad\",\"input\")");
            workbook.write(output);
            workbookBytes = output.toByteArray();
        }

        assertThatThrownBy(() -> reader.read("customers.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbookBytes))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("formulas");
    }

    @Test
    void rejectsFormulaHeadersAndOversizedCsvHeaders() throws Exception {
        byte[] workbookBytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("customers").createRow(0).createCell(0, CellType.FORMULA)
                    .setCellFormula("CONCAT(\"customer\",\"_no\")");
            workbook.write(output);
            workbookBytes = output.toByteArray();
        }

        assertThatThrownBy(() -> reader.read("customers.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbookBytes))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("IMPORT_FORMULA_UNSUPPORTED"));

        byte[] csv = (("h".repeat(257)) + "\nvalue\n").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> reader.read("customers.csv", "text/csv", csv))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("IMPORT_CELL_TOO_LARGE"));
    }

    @Test
    void rejectsCellsAboveTheCharacterLimit() throws Exception {
        StringBuilder oversized = new StringBuilder(10_001);
        Random random = new Random(7);
        while (oversized.length() < 10_001) {
            oversized.append((char) ('a' + random.nextInt(26)));
        }
        byte[] workbookBytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("customers");
            sheet.createRow(0).createCell(0).setCellValue("customer_no");
            sheet.createRow(1).createCell(0).setCellValue(oversized.toString());
            workbook.write(output);
            workbookBytes = output.toByteArray();
        }

        assertThatThrownBy(() -> reader.read("customers.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbookBytes))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("IMPORT_CELL_TOO_LARGE"));
    }

    @Test
    void rejectsZipEntriesAndTotalsAboveConfiguredLimits() throws Exception {
        byte[] archive;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("large.xml"));
            zip.write(new byte[9]);
            zip.closeEntry();
            zip.finish();
            archive = output.toByteArray();
        }

        assertThatThrownBy(() -> MasterDataFileReader.validateZipArchive(archive, 8, 16, 10))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("IMPORT_ARCHIVE_LIMIT_EXCEEDED"));
    }
}
