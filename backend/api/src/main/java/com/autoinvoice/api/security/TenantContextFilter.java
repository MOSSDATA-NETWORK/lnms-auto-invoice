package com.autoinvoice.api.security;

import com.autoinvoice.platform.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class TenantContextFilter extends OncePerRequestFilter {
    private final DatabaseUserDetailsService userDetailsService;
    private final SessionSecurityService sessionSecurityService;

    public TenantContextFilter(DatabaseUserDetailsService userDetailsService,
                               SessionSecurityService sessionSecurityService) {
        this.userDetailsService = userDetailsService;
        this.sessionSecurityService = sessionSecurityService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            AuthenticatedUser current = userDetailsService.findCurrent(user.tenantId(), user.userId()).orElse(null);
            if (current == null || !current.enabled() || !current.credentialsNonExpired()
                    || current.securityVersion() != user.securityVersion()
                    || current.mfaEnabled() != user.mfaEnabled()
                    || current.mustChangePassword() != user.mustChangePassword()
                    || !current.permissions().equals(user.permissions())) {
                sessionSecurityService.invalidate(request);
                filterChain.doFilter(request, response);
                return;
            }
            AuthenticatedUser refreshed = current.forSession(user.mfaVerified());
            if (!refreshed.equals(user)) {
                sessionSecurityService.replaceAuthentication(refreshed, request, false);
            }
            try (TenantContext.Scope ignored = TenantContext.open(refreshed.tenantId())) {
                filterChain.doFilter(request, response);
            }
            return;
        }
        filterChain.doFilter(request, response);
    }
}
