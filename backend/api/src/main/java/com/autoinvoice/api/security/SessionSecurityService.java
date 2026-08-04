package com.autoinvoice.api.security;

import com.autoinvoice.platform.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Map;

import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;

@Service
public class SessionSecurityService {
    private final DatabaseUserDetailsService userDetailsService;

    public SessionSecurityService(DatabaseUserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    public AuthenticatedUser establish(AuthenticatedUser user, boolean mfaVerified, HttpServletRequest request) {
        AuthenticatedUser sessionUser = user.forSession(mfaVerified);
        replaceAuthentication(sessionUser, request, true);
        return sessionUser;
    }

    public AuthenticatedUser refresh(AuthenticatedUser user, boolean mfaVerified, HttpServletRequest request) {
        AuthenticatedUser current = userDetailsService.findCurrent(user.tenantId(), user.userId())
                .filter(AuthenticatedUser::enabled)
                .orElseThrow(() -> new DomainException("AUTHENTICATION_REQUIRED",
                        "The authenticated user is no longer active", 401, Map.of()));
        AuthenticatedUser sessionUser = current.forSession(mfaVerified);
        replaceAuthentication(sessionUser, request, true);
        return sessionUser;
    }

    public void replaceAuthentication(AuthenticatedUser user, HttpServletRequest request, boolean rotateSessionId) {
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                user, null, user.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        HttpSession session = request.getSession(true);
        if (rotateSessionId) {
            request.changeSessionId();
        }
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, context);
    }

    public void invalidate(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    public Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
