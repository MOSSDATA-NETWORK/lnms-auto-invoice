package com.autoinvoice.api.http;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {
    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void preservesSafeRequestIdAndEchoesItToTheResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "req_019-example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (normalized, ignored) ->
                assertThat(((jakarta.servlet.http.HttpServletRequest) normalized)
                        .getHeader(RequestIdFilter.HEADER)).isEqualTo("req_019-example"));

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("req_019-example");
    }

    @Test
    void replacesUnsafeRequestIdBeforeItReachesTheApplication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "bad\r\nInjected: value");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (normalized, ignored) ->
                assertThat(((jakarta.servlet.http.HttpServletRequest) normalized)
                        .getHeader(RequestIdFilter.HEADER)).startsWith("req_").doesNotContain("\r", "\n"));

        assertThat(response.getHeader(RequestIdFilter.HEADER)).startsWith("req_");
    }
}
