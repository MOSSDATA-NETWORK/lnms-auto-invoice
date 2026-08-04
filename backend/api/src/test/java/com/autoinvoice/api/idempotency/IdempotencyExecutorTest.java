package com.autoinvoice.api.idempotency;

import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.platform.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
class IdempotencyExecutorTest {
    private static final byte[] FINGERPRINT_KEY = new byte[32];

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void fingerprintIsStableAcrossObjectKeyOrderAndChangesWithPayload() {
        IdempotencyExecutor executor = new IdempotencyExecutor(null, new ObjectMapper(), null, FINGERPRINT_KEY);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("customer_name", "Example");
        first.put("terms", 30);
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("terms", 30);
        reordered.put("customer_name", "Example");

        assertThat(executor.fingerprint(first)).isEqualTo(executor.fingerprint(reordered));
        assertThat(executor.fingerprint(first)).isNotEqualTo(executor.fingerprint(Map.of("terms", 31)));
    }

    @Test
    void fingerprintIsKeyedToPreventOfflineGuessingFromTheDatabaseHash() {
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        IdempotencyExecutor first = new IdempotencyExecutor(null, new ObjectMapper(), null, FINGERPRINT_KEY);
        IdempotencyExecutor second = new IdempotencyExecutor(null, new ObjectMapper(), null, otherKey);

        Map<String, Object> lowEntropySecret = Map.of("temporary_password", "Password123!");

        assertThat(first.fingerprint(lowEntropySecret)).isNotEqualTo(second.fingerprint(lowEntropySecret));
    }

    @Test
    void ambientActorCannotExecuteAgainstAnotherTenantScope() {
        UUID actorTenant = UUID.randomUUID();
        AuthenticatedUser actor = new AuthenticatedUser(UUID.randomUUID(), actorTenant, "default", "admin",
                "Administrator", "", true, "ciphertext", true, 1, Set.of("system.admin"), true);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(actor, null, actor.getAuthorities()));
        IdempotencyExecutor executor = new IdempotencyExecutor(null, new ObjectMapper(), null, FINGERPRINT_KEY);

        assertThatThrownBy(() -> executor.execute(UUID.randomUUID(), "key", "POST", "/command",
                Map.of(), Void.class, () -> ResponseEntity.noContent().build()))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AUTHENTICATION_REQUIRED"));
    }
}
