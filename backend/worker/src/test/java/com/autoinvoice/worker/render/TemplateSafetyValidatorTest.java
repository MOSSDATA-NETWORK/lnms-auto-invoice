package com.autoinvoice.worker.render;

import com.autoinvoice.template.TemplateSafetyValidator;
import com.autoinvoice.platform.DomainException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateSafetyValidatorTest {
    private final TemplateSafetyValidator validator = new TemplateSafetyValidator();

    @Test
    void acceptsEscapedHandlebarsAndEmbeddedDataAssets() {
        assertThatCode(() -> validator.validate(
                "<h1>{{invoice.number}}</h1><img src=\"data:image/png;base64,AAAA\">",
                "@page { size: A4 } .logo { background:url(data:image/png;base64,AAAA) }"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsVisibleRemoteUrlTextAfterRendering() {
        assertThatCode(() -> validator.validateRenderedDocument(
                "<p>Customer website: https://customer.example/support</p>"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsAllowlistedBlocksAndHelpers() {
        assertThatCode(() -> validator.validate("""
                {{#each invoice.items}}
                  {{#if (eq status "ACTIVE")}}
                    <span>{{formatMoney total_minor "CNY"}}</span>
                  {{else}}
                    <span>{{formatQuantity quantity}} {{formatUnit unit}}</span>
                  {{/if}}
                {{/each}}
                """, ""))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsScriptsRemoteResourcesAndUnescapedExpressions() {
        assertThatThrownBy(() -> validator.validate("<script>alert(1)</script>", ""))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> validator.validate("<img src=\"https://example.test/logo.png\">", ""))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> validator.validate("<div>{{{unsafe}}}</div>", ""))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> validator.validate("<div onclick=\"run()\">x</div>", ""))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsUnknownHelpersAndBrokenBlockStructure() {
        assertThatThrownBy(() -> validator.validate("{{danger invoice.total}}", ""))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("not allowlisted");
        assertThatThrownBy(() -> validator.validate("{{#each invoice.items}}{{/if}}", ""))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("does not match");
        assertThatThrownBy(() -> validator.validate("{{#if invoice.total}}", ""))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("not closed");
    }

    @Test
    void rejectsCustomAndCssEscapedPageSizes() {
        assertThatThrownBy(() -> validator.validate(
                "<p>invoice</p>", "@page { size: 1mm 1mm; }"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("not allowlisted");

        assertThatThrownBy(() -> validator.validate(
                "<p>invoice</p>", "@p\\61 ge { s\\69 ze: 1mm 1mm; }"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("not allowlisted");
    }

    @Test
    void rejectsUnclosedStyleBlocksThatCouldHidePageRules() {
        assertThatThrownBy(() -> validator.validate(
                "<style>@page { size: 1mm 1mm; }", ""))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("style");
    }

    @Test
    void rejectsExcessiveDeclaredLayoutHeight() {
        assertThatThrownBy(() -> validator.validate(
                "<p>invoice</p>", "body { min-height: 120001px; }"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("excessive layout height");

        assertThatThrownBy(() -> validator.validate(
                "<div height=\"120001\">invoice</div>", ""))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("excessive HTML height");
    }

    @Test
    void rejectsOversizedEmbeddedDataResource() {
        String oversizedDataUri = "data:image/png;base64," + "A".repeat(512_001);

        assertThatThrownBy(() -> validator.validate(
                "<img src=\"" + oversizedDataUri + "\">", ""))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("per-resource limit");

        String whitespaceObfuscated = "data:image/png;base64," + "AAAA\n".repeat(130_000);
        assertThatThrownBy(() -> validator.validate(
                "<img src=\"" + whitespaceObfuscated + "\">", ""))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("per-resource limit");

        String rawSvg = "data:image/svg+xml,<svg><text>" + "A".repeat(512_001) + "</text></svg>";
        assertThatThrownBy(() -> validator.validate(
                "<img src=\"" + rawSvg + "\">", ""))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("per-resource limit");
    }

    @Test
    void rejectsAggregateEmbeddedDataResourcesAfterRendering() {
        String first = "data:image/png;base64," + "A".repeat(500_000);
        String second = "data:image/png;base64," + "B".repeat(500_000);

        assertThatThrownBy(() -> validator.validateRenderedDocument(
                "<img src=\"" + first + "\"><img src=\"" + second + "\">"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("aggregate resource limit");

        String manyResources = "<img src=\"data:image/png;base64,A\">".repeat(101);
        assertThatThrownBy(() -> validator.validateRenderedDocument(manyResources))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("too many embedded data resources");

        String maximumResources = "<img src=\"data:image/png;base64,A\">".repeat(100);
        assertThatCode(() -> validator.validateRenderedDocument(
                RenderInvoicePdfHandler.document(maximumResources, "")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPathologicalLayoutNumbersWithoutParsingUnboundedDecimals() {
        String pathologicalHeight = "9".repeat(10_000);

        assertThatThrownBy(() -> validator.validate(
                "<div height=\"" + pathologicalHeight + "\">invoice</div>", ""))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("invalid number");
    }

    @Test
    void revalidatesResourceProtocolsAfterRendering() {
        assertThatThrownBy(() -> validator.validateRenderedDocument(
                "<img src=\"https://customer.example/logo.png\">"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("forbidden content");

        assertThatThrownBy(() -> validator.validateRenderedDocument(
                "<img src=\"https&#58;//customer.example/logo.png\">"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("forbidden content");
    }

    @Test
    void rejectsRemoteResourceIntroducedByHandlebarsExpansion() throws Exception {
        String template = "<img src=\"{{custom.logoUrl}}\">";
        validator.validate(template, "");
        String body = SafeHandlebarsFactory.render(template,
                Map.of("custom", Map.of("logoUrl", "https://customer.example/logo.png")));
        String renderedDocument = RenderInvoicePdfHandler.document(body, "");

        assertThatThrownBy(() -> validator.validateRenderedDocument(renderedDocument))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("forbidden content");
    }
}
