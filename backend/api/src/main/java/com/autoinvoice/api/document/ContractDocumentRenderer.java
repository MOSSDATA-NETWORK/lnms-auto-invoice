package com.autoinvoice.api.document;

import com.autoinvoice.platform.DomainException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Fills {{placeholder}} markers in a Word .docx by rewriting word/document.xml.
 * Word splits a styled sentence into multiple runs, so each paragraph's text
 * runs are merged into the first run before substitution, which keeps paragraph
 * formatting while tolerating markers split across runs.
 */
public final class ContractDocumentRenderer {
    private static final String DOCUMENT_XML = "word/document.xml";
    private static final Pattern PARAGRAPH = Pattern.compile("<w:p\\b[^>]*>.*?</w:p>", Pattern.DOTALL);
    private static final Pattern TEXT = Pattern.compile("<w:t[^>]*>(.*?)</w:t>", Pattern.DOTALL);
    private static final int MAX_TEMPLATE_BYTES = 8 * 1024 * 1024;

    public byte[] render(byte[] template, PlaceholderResolver resolver) {
        if (template == null || template.length == 0 || template.length > MAX_TEMPLATE_BYTES) {
            throw new DomainException("TEMPLATE_TOO_LARGE", "Contract template must be a non-empty docx under 8 MiB", 422, Map.of());
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(template));
             ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zipped = new ZipOutputStream(out)) {
            byte[] buffer = new byte[8192];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) > 0) {
                    content.write(buffer, 0, read);
                }
                byte[] bytes = content.toByteArray();
                if (DOCUMENT_XML.equals(entry.getName())) {
                    bytes = fillDocument(new String(bytes, StandardCharsets.UTF_8), resolver)
                            .getBytes(StandardCharsets.UTF_8);
                }
                zipped.putNextEntry(new ZipEntry(entry.getName()));
                zipped.write(bytes);
                zipped.closeEntry();
            }
            zipped.finish();
            return out.toByteArray();
        } catch (IOException exception) {
            throw new DomainException("TEMPLATE_INVALID", "Unable to read the contract template as a docx archive", 422, Map.of());
        }
    }

    private String fillDocument(String xml, PlaceholderResolver resolver) {
        Matcher paragraph = PARAGRAPH.matcher(xml);
        StringBuffer buffer = new StringBuffer();
        while (paragraph.find()) {
            String block = paragraph.group();
            String filled = fillParagraph(block, resolver);
            paragraph.appendReplacement(buffer, Matcher.quoteReplacement(filled));
        }
        paragraph.appendTail(buffer);
        return buffer.toString();
    }

    private String fillParagraph(String block, PlaceholderResolver resolver) {
        Matcher text = TEXT.matcher(block);
        StringBuilder merged = new StringBuilder();
        while (text.find()) {
            merged.append(text.group(1));
        }
        String raw = merged.toString();
        if (raw.indexOf("{{") < 0) {
            return block;
        }
        String resolved = resolver.resolve(raw);
        String escaped = xmlEscape(resolved);
        boolean[] first = {true};
        StringBuffer buffer = new StringBuffer();
        text.reset();
        while (text.find()) {
            String replacement = first[0] ? escaped : "";
            first[0] = false;
            String tag = text.group();
            int gt = tag.indexOf('>');
            String open = tag.substring(0, gt + 1);
            if (!open.contains("xml:space")) {
                open = open.replace("<w:t", "<w:t xml:space=\"preserve\"");
            }
            text.appendReplacement(buffer, Matcher.quoteReplacement(open + replacement + "</w:t>"));
        }
        text.appendTail(buffer);
        return buffer.toString();
    }

    private String xmlEscape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
