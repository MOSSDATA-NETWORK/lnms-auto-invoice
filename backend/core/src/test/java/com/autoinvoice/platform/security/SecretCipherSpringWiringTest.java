package com.autoinvoice.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecretCipherSpringWiringTest {

    @Test
    void createsCipherFromConfiguredSpringProperty() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "test",
                    Map.of("auto-invoice.security.master-key-base64", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")));
            context.register(SecretCipher.class);
            context.refresh();

            assertThat(context.getBean(SecretCipher.class)).isNotNull();
        }
    }
}
