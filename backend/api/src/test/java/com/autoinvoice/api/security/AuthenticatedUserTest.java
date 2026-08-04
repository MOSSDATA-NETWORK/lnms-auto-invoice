package com.autoinvoice.api.security;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatedUserTest {
    @Test
    void stringRepresentationNeverContainsCredentialMaterial() {
        AuthenticatedUser user = new AuthenticatedUser(
                UUID.randomUUID(), UUID.randomUUID(), "default", "admin", "Administrator",
                "$argon2id$sensitive-password-hash", true, "v1:sensitive-mfa-ciphertext",
                true, 7, Set.of("system.admin"), true);

        assertThat(user.toString())
                .doesNotContain("$argon2id$sensitive-password-hash")
                .doesNotContain("v1:sensitive-mfa-ciphertext")
                .contains("admin");
    }
}
