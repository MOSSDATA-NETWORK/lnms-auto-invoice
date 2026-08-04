package com.autoinvoice.worker.render;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RenderInvoicePdfHandlerTest {
    @Test
    void wrapsRenderedInvoicesInADenyByDefaultContentSecurityPolicy() {
        String document = RenderInvoicePdfHandler.document("<p>invoice</p>", "body { color: black; }");

        assertThat(document)
                .contains("http-equiv=\"Content-Security-Policy\"")
                .contains("default-src 'none'")
                .contains("script-src 'none'")
                .contains("connect-src 'none'")
                .contains("object-src 'none'")
                .contains("base-uri 'none'")
                .contains("form-action 'none'")
                .contains("img-src data:")
                .doesNotContain("http:", "https:", "blob:");
    }

    @Test
    void omitsLiteralNullWhenTemplateCssIsAbsent() {
        String document = RenderInvoicePdfHandler.document("<p>invoice</p>", null);

        assertThat(document)
                .contains("<style></style>")
                .doesNotContain("<style>null");
    }
}
