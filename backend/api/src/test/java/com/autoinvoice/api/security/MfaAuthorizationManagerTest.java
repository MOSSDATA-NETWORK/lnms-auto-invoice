package com.autoinvoice.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MfaAuthorizationManagerTest {
    @Test
    void sensitiveCommandsRequireBothMfaEnrollmentAndVerificationInThisSession() {
        AuthenticatedUser notEnrolled = user(false, false);
        AuthenticatedUser enrolledButNotVerified = user(true, false);
        AuthenticatedUser verified = user(true, true);

        assertThat(MfaAuthorizationManager.isVerified(authentication(notEnrolled))).isFalse();
        assertThat(MfaAuthorizationManager.isVerified(authentication(enrolledButNotVerified))).isFalse();
        assertThat(MfaAuthorizationManager.isVerified(authentication(verified))).isTrue();
    }

    private UsernamePasswordAuthenticationToken authentication(AuthenticatedUser user) {
        return UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
    }

    private AuthenticatedUser user(boolean mfaEnabled, boolean mfaVerified) {
        return new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "default", "admin", "Administrator",
                "", mfaEnabled, mfaEnabled ? "ciphertext" : null, mfaVerified, 1,
                Set.of("system.admin"), true);
    }
}
