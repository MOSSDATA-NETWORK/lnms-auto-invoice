package com.autoinvoice.worker.render;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SafeHandlebarsFactoryTest {
    @Test
    void rendersAllowlistedBlocksConditionsAndFormatters() throws Exception {
        String template = """
                {{#each invoice.items}}
                  {{#if (eq status "ACTIVE")}}{{name}}={{formatMoney total_minor "CNY"}}{{/if}}
                {{/each}}
                """;
        Map<String, Object> model = Map.of("invoice", Map.of("items", List.of(
                Map.of("name", "Bandwidth", "status", "ACTIVE", "total_minor", 12345),
                Map.of("name", "Hidden", "status", "INACTIVE", "total_minor", 500))));

        String rendered = SafeHandlebarsFactory.render(template, model);

        assertThat(rendered).contains("Bandwidth=123.45").doesNotContain("Hidden");
    }

    @Test
    void resolvesOnlyMapValuesAndNeverReflectsOverJavaObjects() throws Exception {
        String template = "{{safe}}|{{unsafe.secret}}|{{unsafe.class.name}}|{{unsafe.getClass.name}}";
        Map<String, Object> model = Map.of("safe", "visible", "unsafe", new SecretHolder());

        String rendered = SafeHandlebarsFactory.render(template, model);

        assertThat(rendered).isEqualTo("visible|||");
    }

    private static final class SecretHolder {
        public String getSecret() {
            throw new AssertionError("JavaBean getters must not be invoked by invoice templates");
        }
    }
}
