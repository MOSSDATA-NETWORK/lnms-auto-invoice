package com.autoinvoice.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.core.util.Json;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiDocumentationConfigurationTest {

    @Test
    void publishesSnakeCaseSchemasAndQueryParameters() {
        ObjectSchema schema = new ObjectSchema();
        schema.addProperty("customerId", new StringSchema());
        schema.addProperty("totalMinor", new IntegerSchema().format("int64"));
        schema.addProperty("version", new IntegerSchema().format("int64"));
        schema.setRequired(java.util.List.of("customerId"));
        ObjectSchema jsonNode = new ObjectSchema();
        Operation operation = new Operation().addParametersItem(
                new Parameter().in("query").name("documentStatus"));
        OpenAPI openApi = new OpenAPI()
                .components(new Components().addSchemas("Example", schema).addSchemas("JsonNode", jsonNode))
                .paths(new Paths().addPathItem("/example", new PathItem().get(operation)));

        OpenApiDocumentationConfiguration.applySnakeCaseContract(openApi);

        assertThat(schema.getProperties()).containsKey("customer_id").doesNotContainKey("customerId");
        assertThat(schema.getProperties()).containsKey("total_minor").doesNotContainKey("totalMinor");
        assertThat(schema.getRequired()).containsExactly("customer_id");
        assertThat(schema.getProperties().get("total_minor").getType()).isEqualTo("string");
        assertThat(schema.getProperties().get("total_minor").getFormat()).isNull();
        assertThat(schema.getProperties().get("total_minor").getPattern()).isEqualTo("^-?\\d+$");
        assertThat(schema.getProperties().get("total_minor").getExample()).isEqualTo("9007199254740993");
        assertThat(schema.getProperties().get("version").getType()).isEqualTo("integer");
        assertThat(schema.getProperties().get("version").getFormat()).isEqualTo("int64");
        assertThat(operation.getParameters().getFirst().getName()).isEqualTo("document_status");
        assertThat(jsonNode.getAdditionalProperties()).isEqualTo(true);

        var serialized = Json.mapper().valueToTree(openApi);
        var serializedTotalMinor = serialized.path("components").path("schemas")
                .path("Example").path("properties").path("total_minor");
        assertThat(serializedTotalMinor.path("type").asText()).isEqualTo("string");
        assertThat(serializedTotalMinor.path("pattern").asText()).isEqualTo("^-?\\d+$");
        assertThat(serializedTotalMinor.path("example").asText()).isEqualTo("9007199254740993");
        assertThat(serializedTotalMinor.has("format")).isFalse();
    }
}
