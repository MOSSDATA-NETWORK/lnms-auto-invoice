package com.autoinvoice.api.security;

import com.autoinvoice.platform.DomainException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PasswordChangeRequiredFilterTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void blocksBusinessApisUntilTheTemporaryPasswordIsChanged() throws Exception {
        HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/invoices");
        MockHttpServletResponse response = new MockHttpServletResponse();
        authenticate(true);

        new PasswordChangeRequiredFilter(resolver).doFilter(request, response, chain);

        var exception = org.mockito.ArgumentCaptor.forClass(Exception.class);
        verify(resolver).resolveException(any(), any(), isNull(), exception.capture());
        verify(chain, never()).doFilter(any(), any());
        assertThat(exception.getValue()).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.code()).isEqualTo("PASSWORD_CHANGE_REQUIRED"));
    }

    @Test
    void allowsThePasswordChangeEndpoint() throws Exception {
        HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/change-password");
        MockHttpServletResponse response = new MockHttpServletResponse();
        authenticate(true);

        new PasswordChangeRequiredFilter(resolver).doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(resolver, never()).resolveException(any(), any(), any(), any());
    }

    @Test
    void blocksOtherAuthenticatedAuthEndpointsInsteadOfAllowingAPathPrefixBypass() throws Exception {
        HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/mfa/enrollment");
        MockHttpServletResponse response = new MockHttpServletResponse();
        authenticate(true);

        new PasswordChangeRequiredFilter(resolver).doFilter(request, response, chain);

        verify(resolver).resolveException(any(), any(), isNull(), any(DomainException.class));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void allowsNormalSessionsToReachBusinessApis() throws Exception {
        HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/invoices");
        MockHttpServletResponse response = new MockHttpServletResponse();
        authenticate(false);

        new PasswordChangeRequiredFilter(resolver).doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(resolver, never()).resolveException(any(), any(), any(), any());
    }

    private void authenticate(boolean mustChangePassword) {
        AuthenticatedUser user = new AuthenticatedUser(
                UUID.randomUUID(), UUID.randomUUID(), "default", "admin", "Administrator", "",
                false, null, false, 1, Set.of("system.admin"), true, true, mustChangePassword);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    }
}
