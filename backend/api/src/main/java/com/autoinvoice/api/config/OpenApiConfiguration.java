package com.autoinvoice.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenAPI autoInvoiceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Auto Invoice API")
                .version("v1")
                .description("Tenant-scoped commercial invoice automation API"));
    }
}

