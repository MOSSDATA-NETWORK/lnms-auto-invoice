package com.autoinvoice.api.security;

import com.autoinvoice.platform.audit.AuditService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AuthenticationThrottleServiceTest {
    @Test
    void bucketHashesAreStableKeyedAndSeparatedFromRawSha256() throws Exception {
        String value = "login-ip:192.0.2.10";
        AuthenticationThrottleService first = service((byte) 1);
        AuthenticationThrottleService sameKey = service((byte) 1);
        AuthenticationThrottleService differentKey = service((byte) 2);
        String rawSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));

        assertThat(first.bucketHash(value)).isEqualTo(sameKey.bucketHash(value));
        assertThat(first.bucketHash(value)).isNotEqualTo(differentKey.bucketHash(value));
        assertThat(first.bucketHash(value)).isNotEqualTo(rawSha256);
        assertThat(first.bucketHash(value)).hasSize(64);
    }

    @Test
    void derivingTheBucketKeyFailsFastForMissingOrInvalidMasterKeys() {
        assertThatThrownBy(() -> AuthenticationThrottleService.deriveBucketKey(""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AuthenticationThrottleService.deriveBucketKey("not-base64"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AuthenticationThrottleService.deriveBucketKey(
                Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
    }

    private AuthenticationThrottleService service(byte fill) {
        byte[] masterKey = new byte[32];
        java.util.Arrays.fill(masterKey, fill);
        byte[] bucketKey = AuthenticationThrottleService.deriveBucketKey(
                Base64.getEncoder().encodeToString(masterKey));
        return new AuthenticationThrottleService(null, mock(AuditService.class), bucketKey);
    }
}
