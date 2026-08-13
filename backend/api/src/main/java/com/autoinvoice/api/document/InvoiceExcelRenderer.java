package com.autoinvoice.api.document;

import com.autoinvoice.platform.DomainException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * Fills {{placeholder}} markers in an .xlsx template by rewriting matching
 * string cells in place, preserving every cell's style and layout.
 */
public final class InvoiceExcelRenderer {
    private static final int MAX_TEMPLATE_BYTES = 8 * 1024 * 1024;

    public byte[] render(byte[] template, PlaceholderResolver resolver) {
        if (template == null || template.length == 0 || template.length > MAX_TEMPLATE_BYTES) {
            throw new DomainException("TEMPLATE_TOO_LARGE", "Invoice template must be a non-empty xlsx under 8 MiB", 422, Map.of());
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            int matched = 0;
            for (Sheet sheet : workbook) {
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        if (cell.getCellType() == CellType.STRING && cell.getStringCellValue().contains("{{")) {
                            String before = cell.getStringCellValue();
                            String after = resolver.resolve(before);
                            cell.setCellValue(after);
                            matched++;
                            org.slf4j.LoggerFactory.getLogger(InvoiceExcelRenderer.class)
                                    .info("Excel placeholder cell {}: [{}] -> [{}]", cell.getAddress(), before, after);
                        }
                    }
                }
            }
            org.slf4j.LoggerFactory.getLogger(InvoiceExcelRenderer.class)
                    .info("Excel template rendered, {} placeholder cells replaced", matched);
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception exception) {
            throw new DomainException("TEMPLATE_INVALID", "Unable to read the invoice template as an xlsx workbook", 422, Map.of());
        }
    }
}
