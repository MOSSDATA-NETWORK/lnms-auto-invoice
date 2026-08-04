package com.autoinvoice.worker.librenms;

import com.autoinvoice.platform.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class LibrenmsJsonSupport {
    private static final DateTimeFormatter SQL_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public List<JsonNode> bills(JsonNode root) {
        JsonNode values = firstNode(root, "bills", "data");
        if (values == null && root.isArray()) {
            values = root;
        }
        if (values == null || !values.isArray()) {
            throw invalid("LibreNMS bills response does not contain an array", "bills");
        }
        List<JsonNode> result = new ArrayList<>();
        values.forEach(result::add);
        return List.copyOf(result);
    }

    public JsonNode exactHistory(JsonNode root, OffsetDateTime expectedStart,
                                 OffsetDateTime expectedEnd, ZoneId sourceZone) {
        JsonNode values = firstNode(root, "bill_history", "history", "data");
        if (values == null && root.isArray()) {
            values = root;
        }
        if (values == null || !values.isArray()) {
            throw invalid("LibreNMS history response does not contain an array", "bill_history");
        }
        for (JsonNode value : values) {
            OffsetDateTime start = requiredTimestamp(value, sourceZone,
                    "bill_datefrom", "period_start", "date_from", "from");
            OffsetDateTime end = requiredTimestamp(value, sourceZone,
                    "bill_dateto", "period_end", "date_to", "to");
            if (start.toInstant().equals(expectedStart.toInstant())
                    && end.toInstant().equals(expectedEnd.toInstant())) {
                return value;
            }
        }
        throw new DomainException("LIBRENMS_HISTORY_PERIOD_MISMATCH",
                "LibreNMS Bill History does not contain the exact requested half-open period", 422,
                Map.of("period_start", expectedStart, "period_end", expectedEnd));
    }

    public long requiredLong(JsonNode node, String... aliases) {
        BigDecimal value = requiredDecimal(node, aliases);
        try {
            return value.longValueExact();
        } catch (ArithmeticException exception) {
            throw invalid("LibreNMS field must be an integer", aliases[0]);
        }
    }

    public Long optionalLong(JsonNode node, String... aliases) {
        BigDecimal value = optionalDecimal(node, aliases);
        if (value == null) {
            return null;
        }
        try {
            return value.longValueExact();
        } catch (ArithmeticException exception) {
            throw invalid("LibreNMS field must be an integer", aliases[0]);
        }
    }

    public BigDecimal requiredDecimal(JsonNode node, String... aliases) {
        BigDecimal value = optionalDecimal(node, aliases);
        if (value == null) {
            throw invalid("Required LibreNMS field is missing or null", aliases[0]);
        }
        return value;
    }

    public BigDecimal optionalDecimal(JsonNode node, String... aliases) {
        JsonNode value = firstNode(node, aliases);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException exception) {
            throw invalid("LibreNMS field is not numeric", aliases[0]);
        }
    }

    public String optionalText(JsonNode node, String... aliases) {
        JsonNode value = firstNode(node, aliases);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    public OffsetDateTime requiredTimestamp(JsonNode node, ZoneId sourceZone, String... aliases) {
        JsonNode value = firstNode(node, aliases);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw invalid("Required LibreNMS timestamp is missing", aliases[0]);
        }
        String text = value.asText().trim();
        if (text.matches("^-?\\d+$")) {
            long epoch = Long.parseLong(text);
            Instant instant = Math.abs(epoch) > 10_000_000_000L
                    ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
            return instant.atOffset(java.time.ZoneOffset.UTC);
        }
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(text, SQL_TIMESTAMP).atZone(sourceZone).toOffsetDateTime();
            } catch (DateTimeParseException exception) {
                throw invalid("LibreNMS timestamp format is unsupported", aliases[0]);
            }
        }
    }

    private JsonNode firstNode(JsonNode node, String... aliases) {
        for (String alias : aliases) {
            JsonNode value = node.get(alias);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private DomainException invalid(String message, String field) {
        return new DomainException("LIBRENMS_RESPONSE_INVALID", message, 422, Map.of("field", field));
    }
}
