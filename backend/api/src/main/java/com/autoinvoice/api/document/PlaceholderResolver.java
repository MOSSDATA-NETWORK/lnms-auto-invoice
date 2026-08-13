package com.autoinvoice.api.document;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {{root.field.field}} placeholders against a JSON model. Unknown
 * placeholders resolve to the empty string so templates never blow up on a
 * missing optional field.
 */
public final class PlaceholderResolver {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*}}");

    private final Map<String, JsonNode> roots;

    public PlaceholderResolver(Map<String, JsonNode> roots) {
        this.roots = roots;
    }

    public String resolve(String text) {
        if (text == null || text.indexOf("{{") < 0) {
            return text;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(value(matcher.group(1))));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    public String value(String path) {
        int dot = path.indexOf('.');
        String root = dot < 0 ? path : path.substring(0, dot);
        String rest = dot < 0 ? "" : path.substring(dot + 1);
        JsonNode node = roots.get(root);
        if (node == null || rest.isEmpty()) {
            return node == null ? "" : node.asText("");
        }
        JsonNode cursor = node;
        for (String segment : rest.split("\\.")) {
            cursor = cursor == null ? null : cursor.path(segment);
            if (cursor == null || cursor.isMissingNode()) {
                return "";
            }
        }
        return cursor.isNull() ? "" : cursor.asText("");
    }
}
