package com.autoinvoice.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class OpenApiDocumentationConfiguration {
    static {
        SpringDocUtils.getConfig().replaceWithSchema(BigDecimal.class,
                new StringSchema().pattern("^-?\\d+(?:\\.\\d+)?$").example("1234.5600"));
    }

    @Bean
    OpenApiCustomizer snakeCaseOpenApiCustomizer() {
        return OpenApiDocumentationConfiguration::applySnakeCaseContract;
    }

    static void applySnakeCaseContract(OpenAPI openApi) {
        if (openApi.getComponents() != null && openApi.getComponents().getSchemas() != null) {
            openApi.getComponents().getSchemas().values().forEach(OpenApiDocumentationConfiguration::renameSchema);
            Schema<?> jsonNode = openApi.getComponents().getSchemas().get("JsonNode");
            if (jsonNode != null) {
                jsonNode.setAdditionalProperties(true);
            }
        }
        if (openApi.getPaths() != null) {
            openApi.getPaths().values().stream()
                    .flatMap(path -> path.readOperations().stream())
                    .filter(operation -> operation.getParameters() != null)
                    .flatMap(operation -> operation.getParameters().stream())
                    .filter(parameter -> "query".equals(parameter.getIn()))
                    .forEach(parameter -> parameter.setName(toSnakeCase(parameter.getName())));
        }
    }

    private static void renameSchema(Schema<?> schema) {
        if (schema == null) {
            return;
        }
        if (schema.getProperties() != null) {
            Map<String, Schema> renamed = new LinkedHashMap<>();
            schema.getProperties().forEach((name, property) -> {
                renameSchema(property);
                String contractName = toSnakeCase(name);
                Schema<?> contractProperty = contractName.endsWith("_minor")
                        ? publishMinorUnitsAsDecimalString(property)
                        : property;
                renamed.put(contractName, contractProperty);
            });
            schema.setProperties(renamed);
        }
        if (schema.getRequired() != null) {
            schema.setRequired(schema.getRequired().stream().map(OpenApiDocumentationConfiguration::toSnakeCase).toList());
        }
        renameSchema(schema.getItems());
        renameSchemas(schema.getAllOf());
        renameSchemas(schema.getAnyOf());
        renameSchemas(schema.getOneOf());
    }

    private static Schema<?> publishMinorUnitsAsDecimalString(Schema<?> source) {
        StringSchema schema = new StringSchema();
        schema.setPattern("^-?\\d+$");
        schema.setExample("9007199254740993");
        schema.setName(source.getName());
        schema.setTitle(source.getTitle());
        schema.setDescription(source.getDescription());
        schema.setNullable(source.getNullable());
        schema.setReadOnly(source.getReadOnly());
        schema.setWriteOnly(source.getWriteOnly());
        schema.setDeprecated(source.getDeprecated());
        schema.setExternalDocs(source.getExternalDocs());
        schema.setXml(source.getXml());
        schema.setExtensions(source.getExtensions());
        return schema;
    }

    private static void renameSchemas(List<Schema> schemas) {
        if (schemas != null) {
            schemas.forEach(OpenApiDocumentationConfiguration::renameSchema);
        }
    }

    private static String toSnakeCase(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
