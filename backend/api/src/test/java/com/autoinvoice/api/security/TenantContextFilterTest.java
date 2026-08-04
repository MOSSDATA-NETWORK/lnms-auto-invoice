package com.autoinvoice.api.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantContextFilterTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void permissionOrSecurityVersionChangesInvalidateTheExistingSession() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AuthenticatedUser sessionUser = user(tenantId, userId, 1, Set.of("system.admin"));
        AuthenticatedUser current = user(tenantId, userId, 2, Set.of("customer.read"));
        DatabaseUserDetailsService userDetails = mock(DatabaseUserDetailsService.class);
        SessionSecurityService sessions = mock(SessionSecurityService.class);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(userDetails.findCurrent(tenantId, userId)).thenReturn(Optional.of(current));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(sessionUser, null, sessionUser.getAuthorities()));

        new TenantContextFilter(userDetails, sessions).doFilter(request, response, chain);

        verify(sessions).invalidate(request);
        verify(chain).doFilter(request, response);
    }

    @Test
    void suspendedTenantInvalidatesAnExistingSessionImmediately() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AuthenticatedUser sessionUser = user(tenantId, userId, 1, Set.of("system.admin"));
        DatabaseUserDetailsService userDetails = mock(DatabaseUserDetailsService.class);
        SessionSecurityService sessions = mock(SessionSecurityService.class);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(userDetails.findCurrent(tenantId, userId)).thenReturn(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(sessionUser, null, sessionUser.getAuthorities()));

        new TenantContextFilter(userDetails, sessions).doFilter(request, response, chain);

        verify(sessions).invalidate(request);
        verify(chain).doFilter(request, response);
    }

    @Test
    void expiredTemporaryPasswordInvalidatesAnExistingSessionImmediately() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AuthenticatedUser sessionUser = user(tenantId, userId, 1, Set.of("system.admin"));
        AuthenticatedUser current = new AuthenticatedUser(
                userId, tenantId, "default", "admin", "Administrator", "", true,
                null, true, 1, Set.of("system.admin"), true, false, true);
        DatabaseUserDetailsService userDetails = mock(DatabaseUserDetailsService.class);
        SessionSecurityService sessions = mock(SessionSecurityService.class);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(userDetails.findCurrent(tenantId, userId)).thenReturn(Optional.of(current));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(sessionUser, null, sessionUser.getAuthorities()));

        new TenantContextFilter(userDetails, sessions).doFilter(request, response, chain);

        verify(sessions).invalidate(request);
        verify(chain).doFilter(request, response);
    }

    private AuthenticatedUser user(UUID tenantId, UUID userId, long securityVersion, Set<String> permissions) {
        return new AuthenticatedUser(userId, tenantId, "default", "admin", "Administrator", "",
                true, "ciphertext", true, securityVersion, permissions, true);
    }
}
