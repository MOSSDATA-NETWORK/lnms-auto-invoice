package com.autoinvoice.platform;

import java.util.Optional;
import java.util.UUID;

public final class TenantContext {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static UUID requireTenantId() {
        UUID tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context is not available");
        }
        return tenantId;
    }

    public static Optional<UUID> currentTenantId() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Scope open(UUID tenantId) {
        UUID previous = CURRENT.get();
        CURRENT.set(tenantId);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}

