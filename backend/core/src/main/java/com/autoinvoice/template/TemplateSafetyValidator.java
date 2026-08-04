package com.autoinvoice.template;

import com.autoinvoice.platform.DomainException;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TemplateSafetyValidator {
    private static final int MAX_HTML_CHARS = 1_000_000;
    private static final int MAX_CSS_CHARS = 500_000;
    private static final int MAX_RENDERED_HTML_CHARS = 4_000_000;
    private static final int MAX_DATA_URI_CHARS = 512_000;
    private static final int MAX_TOTAL_DATA_URI_CHARS = 1_000_000;
    private static final int MAX_DATA_URIS = 100;
    private static final int MAX_DATA_URI_HEADER_CHARS = 256;
    private static final int MAX_LAYOUT_NUMBER_CHARS = 64;
    private static final double MAX_DECLARED_LAYOUT_HEIGHT_PX = 120_000;
    private static final List<String> TEMPLATE_FORBIDDEN = List.of(
            "<script", "javascript:", "vbscript:", "file:", "ftp:", "http://", "https://",
            "@import", "expression(", "-moz-binding", "{{{", "{{&", "<iframe", "<object", "<embed",
            "__proto__", "prototype", "constructor", "@root", "../", "{{lookup", "{{log"
    );
    private static final List<String> RENDERED_STYLE_FORBIDDEN = List.of(
            "javascript:", "vbscript:", "file:", "ftp:", "http://", "https://",
            "@import", "expression(", "-moz-binding"
    );
    private static final Set<String> RENDERED_FORBIDDEN_TAGS = Set.of(
            "script", "iframe", "object", "embed", "base"
    );
    private static final Set<String> ALLOWED_PAGE_SIZES = Set.of(
            "auto", "portrait", "landscape",
            "a3", "a3 portrait", "portrait a3", "a3 landscape", "landscape a3",
            "a4", "a4 portrait", "portrait a4", "a4 landscape", "landscape a4",
            "a5", "a5 portrait", "portrait a5", "a5 landscape", "landscape a5",
            "letter", "letter portrait", "portrait letter", "letter landscape", "landscape letter",
            "legal", "legal portrait", "portrait legal", "legal landscape", "landscape legal"
    );
    private static final Set<String> ROOTS = Set.of(
            "system", "customer", "company", "invoice", "service", "contract", "usage", "custom", "this"
    );
    private static final Set<String> DATA_VARIABLES = Set.of("@index", "@first", "@last", "@key");
    private static final Set<String> BLOCKS = Set.of("each", "if", "unless", "with");
    private static final Set<String> HELPERS = Set.of(
            "formatDate", "formatMoney", "formatQuantity", "formatUnit", "eq", "and", "or", "not"
    );
    private static final Pattern EVENT_HANDLER = Pattern.compile("\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern REMOTE_CSS_URL = Pattern.compile("url\\s*\\(\\s*['\"]?(?!data:)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern STYLE_BLOCK = Pattern.compile("<style\\b[^>]*>(.*?)</style\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern STYLE_OPEN = Pattern.compile("<style\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_CLOSE = Pattern.compile("</style\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_ATTRIBUTE = Pattern.compile(
            "\\sstyle\\s*=\\s*(?:(['\"])(.*?)\\1|([^\\s>]+))",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG_NAME = Pattern.compile(
            "^<\\s*/?\\s*([a-z][a-z0-9:-]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_ATTRIBUTE = Pattern.compile(
            "\\s(src|href|xlink:href|srcset|action|formaction|poster|data|background|cite|longdesc|manifest|profile|ping)"
                    + "\\s*=\\s*(?:(['\"])(.*?)\\2|([^\\s>]+))",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ASCII_CONTROL_OR_SPACE = Pattern.compile("[\\u0000-\\u0020\\u007f]+");
    private static final Pattern HTML_CHARACTER_REFERENCE = Pattern.compile(
            "&(?:#([0-9]{1,7})|#x([0-9a-f]{1,6})|([a-z][a-z0-9]+));?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PAGE_MARKER = Pattern.compile("@page\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAGE_RULE = Pattern.compile("@page(?:\\s+[^{}]+)?\\s*\\{([^{}]*)}",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PAGE_SIZE = Pattern.compile("(?:^|;)\\s*size\\s*:\\s*([^;}]+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LAYOUT_HEIGHT = Pattern.compile(
            "\\b(?:height|min-height|max-height|block-size|min-block-size|max-block-size)\\s*:\\s*([^;}]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_LENGTH = Pattern.compile(
            "([+-]?(?:(?:[0-9]+(?:\\.[0-9]*)?)|(?:\\.[0-9]+))(?:[eE][+-]?[0-9]+)?)\\s*"
                    + "(px|in|cm|mm|q|pt|pc|vh|vw|vmin|vmax|em|rem|ex|ch|lh|rlh|%)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_HEIGHT = Pattern.compile(
            "(?:^|\\s)height\\s*=\\s*['\"]?\\s*([0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HANDLEBARS = Pattern.compile("\\{\\{([^{}]+)}}");
    private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");
    private static final Pattern PATH = Pattern.compile(
            "(?:@[a-z]+|[A-Za-z_][A-Za-z0-9_-]*)(?:(?:\\.[A-Za-z_][A-Za-z0-9_-]*)|(?:\\[[0-9]+]))*");

    public static Set<String> allowedHelpers() {
        return HELPERS;
    }

    public void validate(String html, String css) {
        if (html == null || html.isBlank()) {
            fail("Template HTML is required");
        }
        if (html.length() > MAX_HTML_CHARS || (css != null && css.length() > MAX_CSS_CHARS)) {
            fail("Template exceeds the configured resource limit");
        }
        validateStyleBlocks(html);
        String combined = decoded(html + "\n" + (css == null ? "" : css));
        String styles = decoded(styleSource(html, css));
        validateForbidden(combined, TEMPLATE_FORBIDDEN);
        if (EVENT_HANDLER.matcher(combined).find()) {
            fail("Template event handler attributes are forbidden");
        }
        validateMarkupAndResources(combined, styles);
        if (REMOTE_CSS_URL.matcher(styles).find()) {
            fail("Template CSS may only use embedded data URLs");
        }
        if ((css != null && css.toLowerCase(Locale.ROOT).contains("</style"))
                || html.toLowerCase(Locale.ROOT).contains("<base")) {
            fail("Template attempts to escape the controlled document boundary");
        }
        validateHandlebars(html);
    }

    public void validateRenderedDocument(String html) {
        if (html == null || html.isBlank()) {
            fail("Rendered template HTML is required");
        }
        if (html.length() > MAX_RENDERED_HTML_CHARS) {
            fail("Rendered template exceeds the configured resource limit");
        }
        validateStyleBlocks(html);
        String combined = decoded(html);
        String styles = decoded(styleSource(html, null));
        validateRenderedTags(html);
        validateForbidden(styles, RENDERED_STYLE_FORBIDDEN);
        validateMarkupAndResources(combined, styles);
        if (REMOTE_CSS_URL.matcher(styles).find()) {
            fail("Rendered template CSS may only use embedded data URLs");
        }
    }

    private void validateForbidden(String combined, List<String> forbiddenValues) {
        String lower = combined.toLowerCase(Locale.ROOT);
        forbiddenValues.stream().filter(lower::contains).findFirst()
                .ifPresent(value -> fail("Template contains forbidden content: " + value));
    }

    private void validateMarkupAndResources(String combined, String styles) {
        validateDataUris(decodeHtmlCharacterReferences(combined));
        String withoutComments = CSS_COMMENT.matcher(styles).replaceAll(" ");
        validatePageSizes(withoutComments);
        validateDeclaredLayoutHeight(withoutComments, combined);
    }

    private void validateStyleBlocks(String html) {
        int opens = countMatches(STYLE_OPEN, html);
        int closes = countMatches(STYLE_CLOSE, html);
        int completeBlocks = countMatches(STYLE_BLOCK, html);
        if (opens != closes || opens != completeBlocks) {
            fail("Template contains a malformed or unclosed style block");
        }
    }

    private int countMatches(Pattern pattern, String value) {
        int count = 0;
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private void validateRenderedTags(String html) {
        int cursor = 0;
        while (cursor < html.length()) {
            int start = html.indexOf('<', cursor);
            if (start < 0) {
                return;
            }
            if (html.startsWith("<!--", start)) {
                int commentEnd = html.indexOf("-->", start + 4);
                if (commentEnd < 0) {
                    fail("Rendered template contains an unclosed HTML comment");
                }
                cursor = commentEnd + 3;
                continue;
            }
            int end = findTagEnd(html, start);
            if (end < 0) {
                fail("Rendered template contains a malformed HTML tag");
            }
            String tag = html.substring(start, end + 1);
            Matcher tagName = TAG_NAME.matcher(tag);
            if (tagName.find()) {
                String normalizedTagName = tagName.group(1).toLowerCase(Locale.ROOT);
                if (RENDERED_FORBIDDEN_TAGS.contains(normalizedTagName)) {
                    fail("Template contains forbidden content: <" + normalizedTagName);
                }
                if (EVENT_HANDLER.matcher(tag).find()) {
                    fail("Template event handler attributes are forbidden");
                }
                if ("meta".equals(normalizedTagName)) {
                    String normalizedTag = ASCII_CONTROL_OR_SPACE.matcher(
                                    decodeHtmlCharacterReferences(tag).toLowerCase(Locale.ROOT))
                            .replaceAll("");
                    if (normalizedTag.contains("http-equiv=") && normalizedTag.contains("refresh")) {
                        fail("Template contains forbidden content: meta refresh");
                    }
                }
                validateRenderedUrlAttributes(tag);
            }
            cursor = end + 1;
        }
    }

    private int findTagEnd(String html, int start) {
        char quote = 0;
        for (int index = start + 1; index < html.length(); index++) {
            char current = html.charAt(index);
            if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                }
            } else if (current == '\'' || current == '\"') {
                quote = current;
            } else if (current == '>') {
                return index;
            }
        }
        return -1;
    }

    private void validateRenderedUrlAttributes(String tag) {
        Matcher attribute = URL_ATTRIBUTE.matcher(tag);
        while (attribute.find()) {
            String name = attribute.group(1).toLowerCase(Locale.ROOT);
            if ("srcset".equals(name)) {
                fail("Template contains forbidden content: srcset");
            }
            String value = attribute.group(3) == null ? attribute.group(4) : attribute.group(3);
            String normalized = ASCII_CONTROL_OR_SPACE.matcher(
                            decoded(decodeHtmlCharacterReferences(value)).toLowerCase(Locale.ROOT))
                    .replaceAll("");
            if (!normalized.isEmpty() && !normalized.startsWith("data:") && !normalized.startsWith("#")) {
                fail("Template contains forbidden content: remote resource URL");
            }
        }
    }

    private void validateDataUris(String value) {
        int index = 0;
        int resources = 0;
        int total = 0;
        char quote = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (isDataUriContextBoundary(current)) {
                quote = 0;
                index++;
                continue;
            }
            if (quote == 0 && (current == '\'' || current == '\"')) {
                quote = current;
                index++;
                continue;
            }
            if (current == quote) {
                quote = 0;
                index++;
                continue;
            }
            if (!value.regionMatches(true, index, "data:", 0, 5)) {
                index++;
                continue;
            }
            int end = dataUriEnd(value, index + 5, quote);
            int comma = value.indexOf(',', index + 5);
            if (comma < 0 || comma >= end) {
                index += 5;
                continue;
            }
            if (comma - index > MAX_DATA_URI_HEADER_CHARS) {
                fail("Template data URI header exceeds the resource limit");
            }
            resources++;
            if (resources > MAX_DATA_URIS) {
                fail("Template contains too many embedded data resources");
            }
            int length = dataUriLength(value, index, end);
            if (length > MAX_DATA_URI_CHARS) {
                fail("Template data URI exceeds the per-resource limit");
            }
            total = Math.addExact(total, length);
            if (total > MAX_TOTAL_DATA_URI_CHARS) {
                fail("Template data URIs exceed the aggregate resource limit");
            }
            if (quote != 0 && end < value.length() && value.charAt(end) == quote) {
                quote = 0;
                index = end + 1;
            } else {
                index = end;
            }
        }
    }

    private boolean isDataUriContextBoundary(char value) {
        return "<>(){};".indexOf(value) >= 0;
    }

    private int dataUriEnd(String value, int start, char quote) {
        for (int index = start; index < value.length(); index++) {
            char current = value.charAt(index);
            if (quote != 0) {
                if (current == quote) {
                    return index;
                }
            } else if (current == ')' || current == '<' || current == '>'
                    || current == '\'' || current == '\"' || current == '`'
                    || current == '=' || isAsciiControlOrSpace(current)) {
                return index;
            }
        }
        return value.length();
    }

    private int dataUriLength(String value, int start, int end) {
        return end - start;
    }

    private boolean isAsciiControlOrSpace(char value) {
        return value <= 0x20 || value == 0x7f;
    }

    private void validatePageSizes(String value) {
        int markers = 0;
        Matcher marker = PAGE_MARKER.matcher(value);
        while (marker.find()) {
            markers++;
        }
        int parsedRules = 0;
        Matcher rule = PAGE_RULE.matcher(value);
        while (rule.find()) {
            parsedRules++;
            Matcher size = PAGE_SIZE.matcher(rule.group(1));
            while (size.find()) {
                String normalized = size.group(1)
                        .replaceAll("(?i)\\s*!important\\s*$", "")
                        .trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                if (!ALLOWED_PAGE_SIZES.contains(normalized)) {
                    fail("Template @page size is not allowlisted: " + normalized);
                }
            }
        }
        if (parsedRules != markers) {
            fail("Template contains a malformed @page rule");
        }
    }

    private void validateDeclaredLayoutHeight(String styles, String html) {
        Matcher declaration = LAYOUT_HEIGHT.matcher(styles);
        while (declaration.find()) {
            String expression = declaration.group(1);
            if (expression.toLowerCase(Locale.ROOT).contains("var(")) {
                fail("Template layout height cannot use unresolved CSS variables");
            }
            Matcher length = CSS_LENGTH.matcher(expression);
            double estimatedPixels = 0;
            while (length.find()) {
                double amount = Math.abs(parseLayoutNumber(length.group(1)));
                estimatedPixels += toPixels(amount, length.group(2));
                if (!Double.isFinite(estimatedPixels) || estimatedPixels > MAX_DECLARED_LAYOUT_HEIGHT_PX) {
                    fail("Template declares an excessive layout height");
                }
            }
        }
        Matcher attribute = HTML_HEIGHT.matcher(html);
        while (attribute.find()) {
            double height = parseLayoutNumber(attribute.group(1));
            if (!Double.isFinite(height) || height > MAX_DECLARED_LAYOUT_HEIGHT_PX) {
                fail("Template declares an excessive HTML height");
            }
        }
    }

    private double parseLayoutNumber(String value) {
        if (value.length() > MAX_LAYOUT_NUMBER_CHARS) {
            fail("Template layout height contains an invalid number");
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            fail("Template layout height contains an invalid number");
            return Double.NaN;
        }
    }

    private double toPixels(double value, String unit) {
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "px" -> value;
            case "in" -> value * 96;
            case "cm" -> value * 96 / 2.54;
            case "mm" -> value * 96 / 25.4;
            case "q" -> value * 96 / 101.6;
            case "pt" -> value * 96 / 72;
            case "pc" -> value * 16;
            case "vh" -> value * 7.2;
            case "vw" -> value * 12.8;
            case "vmin" -> value * 7.2;
            case "vmax" -> value * 12.8;
            case "em", "rem" -> value * 16;
            case "ex", "ch" -> value * 8;
            case "lh", "rlh" -> value * 24;
            case "%" -> value * 12;
            default -> Double.POSITIVE_INFINITY;
        };
    }

    private String styleSource(String html, String css) {
        StringBuilder styles = new StringBuilder(css == null ? "" : css);
        Matcher blocks = STYLE_BLOCK.matcher(html);
        while (blocks.find()) {
            styles.append('\n').append(blocks.group(1));
        }
        Matcher attributes = STYLE_ATTRIBUTE.matcher(html);
        while (attributes.find()) {
            String value = attributes.group(2) == null ? attributes.group(3) : attributes.group(2);
            styles.append('\n').append(decodeHtmlCharacterReferences(value));
        }
        return styles.toString();
    }

    private String decodeHtmlCharacterReferences(String value) {
        Matcher matcher = HTML_CHARACTER_REFERENCE.matcher(value);
        StringBuilder result = new StringBuilder(value.length());
        while (matcher.find()) {
            String replacement = decodeHtmlCharacterReference(matcher);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String decodeHtmlCharacterReference(Matcher matcher) {
        if (matcher.group(1) != null || matcher.group(2) != null) {
            int radix = matcher.group(1) == null ? 16 : 10;
            String digits = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            try {
                int codePoint = Integer.parseInt(digits, radix);
                if (Character.isValidCodePoint(codePoint) && codePoint != 0) {
                    return new String(Character.toChars(codePoint));
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid references remain visible to the validator as replacement characters.
            }
            return "\ufffd";
        }
        return switch (matcher.group(3).toLowerCase(Locale.ROOT)) {
            case "amp" -> "&";
            case "apos" -> "'";
            case "colon" -> ":";
            case "gt" -> ">";
            case "lt" -> "<";
            case "newline" -> "\n";
            case "quot" -> "\"";
            case "sol" -> "/";
            case "tab" -> "\t";
            default -> matcher.group();
        };
    }

    private String decoded(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '\\' || index + 1 >= value.length()) {
                result.append(current);
                continue;
            }
            int next = index + 1;
            char escaped = value.charAt(next);
            if (escaped == '\n' || escaped == '\r' || escaped == '\f') {
                index = next;
                if (escaped == '\r' && index + 1 < value.length() && value.charAt(index + 1) == '\n') {
                    index++;
                }
                continue;
            }
            int digit = Character.digit(escaped, 16);
            if (digit < 0) {
                result.append(escaped);
                index = next;
                continue;
            }
            int codePoint = 0;
            int digits = 0;
            while (next < value.length() && digits < 6) {
                digit = Character.digit(value.charAt(next), 16);
                if (digit < 0) {
                    break;
                }
                codePoint = codePoint * 16 + digit;
                next++;
                digits++;
            }
            if (next < value.length() && Character.isWhitespace(value.charAt(next))) {
                next++;
            }
            result.appendCodePoint(Character.isValidCodePoint(codePoint) && codePoint != 0 ? codePoint : 0xfffd);
            index = next - 1;
        }
        return result.toString();
    }

    private void validateHandlebars(String html) {
        Matcher matcher = HANDLEBARS.matcher(html);
        Deque<String> blocks = new ArrayDeque<>();
        int cursor = 0;
        while (matcher.find()) {
            rejectUnclosedDelimiter(html.substring(cursor, matcher.start()));
            cursor = matcher.end();
            String token = matcher.group(1).trim();
            if (token.isEmpty()) {
                fail("Template contains an empty Handlebars expression");
            }
            if (token.startsWith("!")) {
                continue;
            }
            if (token.equals("else")) {
                if (blocks.isEmpty()) {
                    fail("Template contains {{else}} outside a block");
                }
                continue;
            }
            if (token.startsWith("#")) {
                validateOpeningBlock(token.substring(1).trim(), blocks);
                continue;
            }
            if (token.startsWith("/")) {
                validateClosingBlock(token.substring(1).trim(), blocks);
                continue;
            }
            validateExpression(token);
        }
        rejectUnclosedDelimiter(html.substring(cursor));
        if (!blocks.isEmpty()) {
            fail("Template block is not closed: " + blocks.peek());
        }
    }

    private void validateOpeningBlock(String expression, Deque<String> blocks) {
        List<String> parts = splitTopLevel(expression);
        if (parts.size() != 2 || !BLOCKS.contains(parts.getFirst())) {
            fail("Template block is not allowlisted or has invalid arguments: " + expression);
        }
        if (Set.of("each", "with").contains(parts.getFirst())) {
            validateValue(parts.get(1));
        } else {
            validateExpression(parts.get(1));
        }
        blocks.push(parts.getFirst());
    }

    private void validateClosingBlock(String expression, Deque<String> blocks) {
        if (!BLOCKS.contains(expression) || blocks.isEmpty() || !blocks.peek().equals(expression)) {
            fail("Template block closing tag does not match: " + expression);
        }
        blocks.pop();
    }

    private void validateExpression(String expression) {
        String value = expression.trim();
        if (value.startsWith("(") || value.endsWith(")")) {
            if (!isWrappedExpression(value)) {
                fail("Template helper expression has unbalanced parentheses: " + expression);
            }
            validateHelperInvocation(value.substring(1, value.length() - 1).trim());
            return;
        }
        List<String> parts = splitTopLevel(value);
        if (parts.size() == 1) {
            validateValue(parts.getFirst());
            return;
        }
        validateHelperInvocation(value);
    }

    private void validateHelperInvocation(String expression) {
        List<String> parts = splitTopLevel(expression);
        if (parts.size() < 2 || !HELPERS.contains(parts.getFirst())) {
            String helper = parts.isEmpty() ? expression : parts.getFirst();
            fail("Template helper is not allowlisted: " + helper);
        }
        for (int index = 1; index < parts.size(); index++) {
            String argument = parts.get(index);
            if (argument.startsWith("(") || argument.endsWith(")")) {
                validateExpression(argument);
            } else {
                validateValue(argument);
            }
        }
    }

    private void validateValue(String value) {
        if (isQuoted(value) || NUMBER.matcher(value).matches()
                || Set.of("true", "false", "null").contains(value)) {
            return;
        }
        if (!PATH.matcher(value).matches()) {
            fail("Template value is not a safe literal or path: " + value);
        }
        if (value.startsWith("@")) {
            if (!DATA_VARIABLES.contains(value)) {
                fail("Template data variable is not allowlisted: " + value);
            }
            return;
        }
        String root = value.split("[.\\[]", 2)[0];
        boolean localField = !value.contains(".") && !value.contains("[")
                && value.matches("[A-Za-z_][A-Za-z0-9_-]*");
        if (!ROOTS.contains(root) && !localField) {
            fail("Template variable root is not allowlisted: " + root);
        }
    }

    private List<String> splitTopLevel(String expression) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parentheses = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < expression.length(); index++) {
            char value = expression.charAt(index);
            if (quote != 0) {
                current.append(value);
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == quote) {
                    quote = 0;
                }
                continue;
            }
            if (value == '\'' || value == '"') {
                quote = value;
                current.append(value);
            } else if (value == '(') {
                parentheses++;
                current.append(value);
            } else if (value == ')') {
                parentheses--;
                if (parentheses < 0) {
                    fail("Template helper expression has unbalanced parentheses: " + expression);
                }
                current.append(value);
            } else if (Character.isWhitespace(value) && parentheses == 0) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(value);
            }
        }
        if (quote != 0 || parentheses != 0) {
            fail("Template helper expression is not balanced: " + expression);
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private boolean isWrappedExpression(String value) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == '(') {
                depth++;
            } else if (current == ')' && --depth == 0 && index != value.length() - 1) {
                return false;
            } else if (depth < 0) {
                return false;
            }
        }
        return depth == 0 && quote == 0;
    }

    private boolean isQuoted(String value) {
        return value.length() >= 2
                && ((value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
                || (value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\''));
    }

    private void rejectUnclosedDelimiter(String value) {
        if (value.contains("{{") || value.contains("}}")) {
            fail("Template contains an unclosed Handlebars expression");
        }
    }

    private void fail(String message) {
        throw new DomainException("TEMPLATE_VALIDATION_FAILED", message, 422, Map.of());
    }
}
