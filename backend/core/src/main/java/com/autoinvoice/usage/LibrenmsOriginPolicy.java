package com.autoinvoice.usage;

import com.autoinvoice.platform.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class LibrenmsOriginPolicy {
    private final Set<String> allowedOrigins;

    public LibrenmsOriginPolicy(
            @Value("${auto-invoice.librenms.allowed-origins:}") String configuredOrigins) {
        LinkedHashSet<String> origins = new LinkedHashSet<>();
        Arrays.stream(configuredOrigins == null ? new String[0] : configuredOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(this::configuredOrigin)
                .forEach(origins::add);
        this.allowedOrigins = Set.copyOf(origins);
    }

    public URI requireAllowed(String rawOrigin) {
        if (allowedOrigins.isEmpty()) {
            throw new DomainException("LIBRENMS_ORIGINS_NOT_CONFIGURED",
                    "LibreNMS outbound connections are disabled until an origin allowlist is configured", 503,
                    Map.of());
        }
        URI origin = canonicalOrigin(rawOrigin);
        if (!allowedOrigins.contains(origin.toString())) {
            throw new DomainException("LIBRENMS_ORIGIN_NOT_ALLOWED",
                    "LibreNMS base URL is not present in the configured origin allowlist", 422,
                    Map.of("origin", origin.toString()));
        }
        return origin;
    }

    private String configuredOrigin(String value) {
        try {
            return canonicalOrigin(value).toString();
        } catch (DomainException exception) {
            throw new IllegalStateException("LIBRENMS_ALLOWED_ORIGINS contains an invalid origin: " + value,
                    exception);
        }
    }

    private URI canonicalOrigin(String value) {
        URI uri;
        try {
            uri = new URI(value == null ? "" : value.trim());
        } catch (URISyntaxException exception) {
            throw invalid();
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        String rawPath = uri.getRawPath();
        if (!("http".equals(scheme) || "https".equals(scheme))
                || uri.isOpaque() || host == null || host.isBlank()
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (rawPath != null && !rawPath.isEmpty() && !"/".equals(rawPath))) {
            throw invalid();
        }
        int port = uri.getPort();
        if (port == 0 || port > 65_535) {
            throw invalid();
        }
        host = normalizeHost(host);
        if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
            port = -1;
        }
        String authority = host + (port == -1 ? "" : ":" + port);
        return URI.create(scheme + "://" + authority);
    }

    private String normalizeHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("[") && normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            throw invalid();
        }
        return normalized;
    }

    private DomainException invalid() {
        return new DomainException("LIBRENMS_ORIGIN_INVALID",
                "LibreNMS base URL must be an HTTP(S) origin without credentials, query, fragment or a non-root path",
                422, Map.of());
    }
}
