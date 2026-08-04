package com.autoinvoice.api.security;

import com.autoinvoice.platform.DomainException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@Component
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {
    private static final Set<String> ALLOWED_PATHS = Set.of(
            "/api/v1/auth/csrf",
            "/api/v1/auth/sign-in",
            "/api/v1/auth/mfa/verify",
            "/api/v1/auth/session",
            "/api/v1/auth/sign-out",
            "/api/v1/auth/change-password"
    );

    private final HandlerExceptionResolver exceptionResolver;

    public PasswordChangeRequiredFilter(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/") || ALLOWED_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user
                && user.mustChangePassword()) {
            exceptionResolver.resolveException(request, response, null,
                    new DomainException("PASSWORD_CHANGE_REQUIRED",
                            "Change the temporary password before using the application", 403, Map.of()));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
