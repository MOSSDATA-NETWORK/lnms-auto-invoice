package com.autoinvoice.api.auth;

import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.api.security.AuthenticationThrottleService;
import com.autoinvoice.api.security.MfaChallengeService;
import com.autoinvoice.api.security.SessionSecurityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerSecurityTest {
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private AuthenticationThrottleService throttleService;
    @Mock
    private MfaChallengeService challengeService;
    @Mock
    private SessionSecurityService sessionSecurityService;
    @Mock
    private JdbcClient jdbc;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpSession session;

    private AuthController controller;

    @BeforeEach
    void setUp() {
        controller = new AuthController(authenticationManager, throttleService, challengeService,
                sessionSecurityService, jdbc);
    }

    @Test
    void failedPasswordAuthenticationUsesOnlyTheAtomicReservationWithoutDoubleCounting() {
        AuthenticationThrottleService.LoginAttempt attempt = loginAttempt();
        when(throttleService.loginAttempt("default", "admin", request)).thenReturn(attempt);
        when(throttleService.reserveLoginAttempt(attempt, request)).thenReturn(true);
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("internal detail"));

        assertThatThrownBy(() -> controller.signIn(
                new AuthController.SignInRequest("default", "admin", "wrong-password"), request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode().value()).isEqualTo(401);
                    assertThat(exception.getReason()).isEqualTo("Invalid credentials");
                });

        verify(throttleService).reserveLoginAttempt(attempt, request);
        verify(throttleService).recordLoginFailure(attempt, "INVALID_CREDENTIALS", request);
        verify(throttleService, never()).recordLoginSuccess(attempt);
    }

    @Test
    void revokedMfaChallengeIsRemovedAfterTheFailureLimit() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        MfaChallengeService.PendingChallenge pending = new MfaChallengeService.PendingChallenge(
                UUID.randomUUID(), tenantId, userId);
        AuthenticationThrottleService.MfaAttempt attempt = new AuthenticationThrottleService.MfaAttempt(
                new AuthenticationThrottleService.Identity(tenantId, userId, "Administrator"), "user", "ip");
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(anyString())).thenReturn(pending);
        when(session.getId()).thenReturn("session-id");
        when(throttleService.mfaAttempt(tenantId, userId, request)).thenReturn(attempt);
        when(throttleService.mfaAllowed(attempt, request)).thenReturn(true);
        when(challengeService.verify(pending, "session-id", "000000"))
                .thenReturn(new MfaChallengeService.Verification(false, true, null));

        assertThatThrownBy(() -> controller.verifyMfa(new AuthController.MfaRequest("000000"), request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(401));

        verify(throttleService).mfaAllowed(attempt, request);
        verify(throttleService).recordMfaFailure(attempt, "auth.mfa_verification_failed", true, request);
        verify(session).removeAttribute(anyString());
    }

    private AuthenticationThrottleService.LoginAttempt loginAttempt() {
        return new AuthenticationThrottleService.LoginAttempt(
                new AuthenticationThrottleService.Identity(UUID.randomUUID(), UUID.randomUUID(), "Administrator"),
                "identity", "ip");
    }
}
