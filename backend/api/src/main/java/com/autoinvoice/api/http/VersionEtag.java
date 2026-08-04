package com.autoinvoice.api.http;

import com.autoinvoice.platform.DomainException;

import java.util.Map;

public final class VersionEtag {
    private VersionEtag() {
    }

    public static long parse(String value) {
        try {
            if (value == null || value.isBlank() || "*".equals(value.trim())) {
                throw new NumberFormatException("Missing numeric entity tag");
            }
            return Long.parseLong(value.trim().replace("W/", "").replace("\"", ""));
        } catch (NumberFormatException exception) {
            throw new DomainException("VERSION_CONFLICT",
                    "If-Match must contain the current numeric version", 409, Map.of());
        }
    }

    public static String format(long version) {
        return "\"" + version + "\"";
    }
}
