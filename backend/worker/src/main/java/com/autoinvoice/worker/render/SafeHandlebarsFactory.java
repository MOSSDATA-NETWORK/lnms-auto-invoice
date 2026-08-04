package com.autoinvoice.worker.render;

import com.autoinvoice.template.TemplateSafetyValidator;
import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Options;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.context.MapValueResolver;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

final class SafeHandlebarsFactory {
    private static final Map<String, Integer> CURRENCY_SCALES = Map.of(
            "CNY", 2, "USD", 2, "HKD", 2, "JPY", 0);
    private static final Set<String> REGISTERED_HELPERS = Set.of(
            "formatDate", "formatMoney", "formatQuantity", "formatUnit", "eq", "and", "or", "not");

    private SafeHandlebarsFactory() {
    }

    static Handlebars create() {
        if (!REGISTERED_HELPERS.equals(TemplateSafetyValidator.allowedHelpers())) {
            throw new IllegalStateException("Template validator and renderer helper allowlists differ");
        }
        Handlebars handlebars = new Handlebars();
        handlebars.registerHelper("formatDate", SafeHandlebarsFactory::formatDate);
        handlebars.registerHelper("formatMoney", SafeHandlebarsFactory::formatMoney);
        handlebars.registerHelper("formatQuantity", SafeHandlebarsFactory::formatQuantity);
        handlebars.registerHelper("formatUnit", SafeHandlebarsFactory::formatUnit);
        handlebars.registerHelper("eq", SafeHandlebarsFactory::equal);
        handlebars.registerHelper("and", SafeHandlebarsFactory::and);
        handlebars.registerHelper("or", SafeHandlebarsFactory::or);
        handlebars.registerHelper("not", SafeHandlebarsFactory::not);
        return handlebars;
    }

    static String render(String source, Map<String, Object> model) throws Exception {
        Template template = create().compileInline(source);
        Context context = Context.newBuilder(model)
                .resolver(MapValueResolver.INSTANCE)
                .build();
        try {
            return template.apply(context);
        } finally {
            context.destroy();
        }
    }

    private static Object formatDate(Object context, Options options) {
        if (context == null) {
            return "";
        }
        String pattern = parameter(options, 0) == null ? "yyyy-MM-dd" : parameter(options, 0).toString();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
        if (context instanceof TemporalAccessor temporal) {
            return formatter.format(temporal);
        }
        String value = context.toString();
        try {
            return formatter.format(LocalDate.parse(value));
        } catch (DateTimeParseException ignored) {
            try {
                return formatter.format(OffsetDateTime.parse(value));
            } catch (DateTimeParseException ignoredAgain) {
                try {
                    return formatter.withZone(java.time.ZoneOffset.UTC).format(Instant.parse(value));
                } catch (DateTimeParseException ignoredFinally) {
                    return value;
                }
            }
        }
    }

    private static Object formatMoney(Object context, Options options) {
        if (context == null) {
            return "";
        }
        Object scaleParameter = parameter(options, 0);
        int scale = scaleParameter instanceof Number number
                ? number.intValue()
                : CURRENCY_SCALES.getOrDefault(scaleParameter == null ? "" : scaleParameter.toString().toUpperCase(), 2);
        if (scale < 0 || scale > 6) {
            throw new IllegalArgumentException("Currency scale must be between 0 and 6");
        }
        return decimal(context).movePointLeft(scale).setScale(scale, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static Object formatQuantity(Object context, Options options) {
        if (context == null) {
            return "";
        }
        BigDecimal value = decimal(context).stripTrailingZeros();
        return value.signum() == 0 ? "0" : value.toPlainString();
    }

    private static Object formatUnit(Object context, Options options) {
        if (context == null) {
            return "";
        }
        Object unit = parameter(options, 0);
        return unit == null ? context.toString().trim()
                : formatQuantity(context, options) + " " + unit.toString().trim();
    }

    private static Object equal(Object context, Options options) {
        Object other = parameter(options, 0);
        if (context instanceof Number || other instanceof Number) {
            try {
                return decimal(context).compareTo(decimal(other)) == 0;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        return java.util.Objects.equals(context, other);
    }

    private static Object and(Object context, Options options) {
        if (!truthy(context)) {
            return false;
        }
        for (Object parameter : options.params) {
            if (!truthy(parameter)) {
                return false;
            }
        }
        return true;
    }

    private static Object or(Object context, Options options) {
        if (truthy(context)) {
            return true;
        }
        for (Object parameter : options.params) {
            if (truthy(parameter)) {
                return true;
            }
        }
        return false;
    }

    private static Object not(Object context, Options options) {
        return !truthy(context);
    }

    private static Object parameter(Options options, int index) {
        return options.params.length > index ? options.params[index] : null;
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Numeric template value is required");
        }
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    private static boolean truthy(Object value) {
        if (value == null || Boolean.FALSE.equals(value)) {
            return false;
        }
        if (value instanceof Number number && new BigDecimal(number.toString()).signum() == 0) {
            return false;
        }
        if (value instanceof CharSequence sequence) {
            return !sequence.isEmpty();
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return !value.getClass().isArray() || Array.getLength(value) > 0;
    }
}
