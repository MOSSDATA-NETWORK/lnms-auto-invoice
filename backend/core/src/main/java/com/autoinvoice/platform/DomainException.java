package com.autoinvoice.platform;

import java.util.Map;

public final class DomainException extends RuntimeException {
    private final String code;
    private final int status;
    private final Map<String, Object> details;

    public DomainException(String code, String message) {
        this(code, message, 422, Map.of());
    }

    public DomainException(String code, String message, int status, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = Map.copyOf(details);
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public Map<String, Object> details() {
        return details;
    }
}

