package com.autoinvoice.platform.security;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {
    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-000000000001");

    @Test
    void roundTripsWithTenantAndPurposeBoundAsAuthenticatedData() {
        SecretCipher cipher = new SecretCipher(new byte[32], new SecureRandom(new byte[]{1, 2, 3}));
        String encrypted = cipher.encrypt("JBSWY3DPEHPK3PXP", TENANT, "user-mfa:user-1");

        assertThat(encrypted).startsWith("v1:").doesNotContain("JBSWY3DPEHPK3PXP");
        assertThat(cipher.decrypt(encrypted, TENANT, "user-mfa:user-1")).isEqualTo("JBSWY3DPEHPK3PXP");
        assertThatThrownBy(() -> cipher.decrypt(encrypted, TENANT, "librenms-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failed authentication");
    }

    @Test
    void refusesToConstructWithoutAConfiguredKey() {
        assertThatThrownBy(() -> new SecretCipher(new byte[0], new SecureRandom()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
