package com.autoinvoice.api.auth;

import com.autoinvoice.api.security.AuthenticatedUser;
import com.autoinvoice.api.security.AuthenticationThrottleService;
import com.autoinvoice.api.security.MfaChallengeService;
import com.autoinvoice.api.security.SessionSecurityService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private static final String PENDING_MFA = AuthController.class.getName() + ".PENDING_MFA";
    private final AuthenticationManager authenticationManager;
    private final AuthenticationThrottleService throttleService;
    private final MfaChallengeService challengeService;
    private final SessionSecurityService sessionSecurityService;
    private final JdbcClient jdbc;

    public AuthController(AuthenticationManager authenticationManager,
                          AuthenticationThrottleService throttleService,
                          MfaChallengeService challengeService,
                          SessionSecurityService sessionSecurityService,
                          JdbcClient jdbc) {
        this.authenticationManager = authenticationManager;
        this.throttleService = throttleService;
        this.challengeService = challengeService;
        this.sessionSecurityService = sessionSecurityService;
        this.jdbc = jdbc;
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getToken(), token.getHeaderName());
    }

    @PostMapping("/sign-in")
    public SignInResponse signIn(@Valid @RequestBody SignInRequest request, HttpServletRequest servletRequest) {
        AuthenticationThrottleService.LoginAttempt attempt = throttleService.loginAttempt(
                request.tenantCode(), request.username(), servletRequest);
        if (!throttleService.reserveLoginAttempt(attempt, servletRequest)) {
            throw throttleService.throttled();
        }
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            request.tenantCode() + ":" + request.username(), request.password()));
        } catch (AuthenticationException exception) {
            throttleService.recordLoginFailure(attempt, "INVALID_CREDENTIALS", servletRequest);
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Invalid credentials");
        }
        throttleService.recordLoginSuccess(attempt);
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        if (user.mfaEnabled()) {
            HttpSession session = servletRequest.getSession(true);
            session.removeAttribute(SPRING_SECURITY_CONTEXT_KEY);
            SecurityContextHolder.clearContext();
            servletRequest.changeSessionId();
            MfaChallengeService.PendingChallenge challenge = challengeService.create(user, session.getId());
            session.setAttribute(PENDING_MFA, challenge);
            return new SignInResponse(true, null);
        }
        AuthenticatedUser sessionUser = sessionSecurityService.establish(user, false, servletRequest);
        recordLogin(sessionUser);
        return new SignInResponse(false, SessionResponse.from(sessionUser));
    }

    @PostMapping("/mfa/verify")
    public SessionResponse verifyMfa(@Valid @RequestBody MfaRequest request, HttpServletRequest servletRequest) {
        HttpSession session = servletRequest.getSession(false);
        if (session == null || !(session.getAttribute(PENDING_MFA)
                instanceof MfaChallengeService.PendingChallenge pending)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "MFA challenge is not active");
        }
        AuthenticationThrottleService.MfaAttempt attempt = throttleService.mfaAttempt(
                pending.tenantId(), pending.userId(), servletRequest);
        if (!throttleService.mfaAllowed(attempt, servletRequest)) {
            throw throttleService.throttled();
        }
        MfaChallengeService.Verification verification = challengeService.verify(pending, session.getId(), request.code());
        if (!verification.verified()) {
            throttleService.recordMfaFailure(attempt, "auth.mfa_verification_failed",
                    verification.challengeRevoked(), servletRequest);
            if (verification.challengeRevoked()) {
                session.removeAttribute(PENDING_MFA);
            }
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Invalid MFA code");
        }
        throttleService.recordMfaSuccess(attempt);
        session.removeAttribute(PENDING_MFA);
        AuthenticatedUser user = sessionSecurityService.establish(verification.user(), true, servletRequest);
        recordLogin(user);
        return SessionResponse.from(user);
    }

    @GetMapping("/session")
    public SessionResponse session(Authentication authentication) {
        return SessionResponse.from((AuthenticatedUser) authentication.getPrincipal());
    }

    @PostMapping("/sign-out")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void signOut(HttpServletRequest request) {
        sessionSecurityService.invalidate(request);
    }

    private void recordLogin(AuthenticatedUser user) {
        jdbc.sql("UPDATE users SET last_login_at = now() WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", user.tenantId()).param("id", user.userId()).update();
    }

    public record SignInRequest(@NotBlank @Size(max = 64) String tenantCode,
                                @NotBlank @Size(max = 320) String username,
                                @NotBlank @Size(max = 200) String password) {
    }

    public record MfaRequest(
            @NotBlank @Size(min = 6, max = 14)
            @Pattern(regexp = "(?:\\d{6}|[A-Za-z2-7]{4}-?[A-Za-z2-7]{4}-?[A-Za-z2-7]{4})") String code) {
    }

    public record SignInResponse(boolean mfaRequired, SessionResponse session) {
    }

    public record CsrfResponse(String token, String headerName) {
    }

    public record SessionResponse(UUID userId, UUID tenantId, String tenantCode, String username,
                                  String displayName, Set<String> permissions, boolean mustChangePassword,
                                  boolean mfaEnabled) {
        static SessionResponse from(AuthenticatedUser user) {
            return new SessionResponse(user.userId(), user.tenantId(), user.tenantCode(), user.username(),
                    user.displayName(), user.permissions(), user.mustChangePassword(), user.mfaEnabled());
        }
    }
}
