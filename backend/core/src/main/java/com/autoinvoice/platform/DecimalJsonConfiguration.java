package com.autoinvoice.platform;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;
import java.util.List;

@Configuration
public class DecimalJsonConfiguration {
    @Bean
    JacksonModule decimalStringJsonModule() {
        SimpleModule module = new SimpleModule("auto-invoice-decimal-strings");
        module.addSerializer(BigDecimal.class, ToStringSerializer.instance);
        module.setSerializerModifier(new MinorUnitStringSerializerModifier());
        module.addSerializer(com.fasterxml.jackson.databind.JsonNode.class, new LegacyJsonNodeBridgeSerializer());
        module.addDeserializer(com.fasterxml.jackson.databind.JsonNode.class, new LegacyJsonNodeBridgeDeserializer());
        return module;
    }

    @Bean
    com.fasterxml.jackson.databind.ObjectMapper legacyObjectMapper() {
        com.fasterxml.jackson.databind.module.SimpleModule module =
                new com.fasterxml.jackson.databind.module.SimpleModule("auto-invoice-legacy-decimal-strings");
        module.addSerializer(BigDecimal.class, com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance);
        module.setSerializerModifier(new LegacyMinorUnitStringSerializerModifier());
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .registerModule(module);
    }

    private static boolean isMinorUnitLong(String propertyName, Class<?> rawType) {
        return (rawType == long.class || rawType == Long.class)
                && (propertyName.endsWith("Minor") || propertyName.endsWith("_minor"));
    }

    private static final class LegacyJsonNodeBridgeSerializer
            extends tools.jackson.databind.ser.std.StdSerializer<com.fasterxml.jackson.databind.JsonNode> {
        private LegacyJsonNodeBridgeSerializer() {
            super(com.fasterxml.jackson.databind.JsonNode.class);
        }

        @Override
        public void serialize(com.fasterxml.jackson.databind.JsonNode value,
                              tools.jackson.core.JsonGenerator generator,
                              tools.jackson.databind.SerializationContext context) {
            generator.writeRawValue(value.toString());
        }
    }

    private static final class LegacyJsonNodeBridgeDeserializer
            extends tools.jackson.databind.deser.std.StdDeserializer<com.fasterxml.jackson.databind.JsonNode> {
        private static final com.fasterxml.jackson.databind.ObjectMapper LEGACY_MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();

        private LegacyJsonNodeBridgeDeserializer() {
            super(com.fasterxml.jackson.databind.JsonNode.class);
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode deserialize(
                tools.jackson.core.JsonParser parser,
                tools.jackson.databind.DeserializationContext context) {
            tools.jackson.databind.JsonNode tree = context.readTree(parser);
            try {
                return LEGACY_MAPPER.readTree(tree.toString());
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new IllegalArgumentException("Request payload contains invalid JSON", exception);
            }
        }
    }

    private static final class MinorUnitStringSerializerModifier extends ValueSerializerModifier {
        @Override
        public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                                                          tools.jackson.databind.BeanDescription.Supplier beanDesc,
                                                          List<BeanPropertyWriter> properties) {
            properties.stream()
                    .filter(property -> isMinorUnitLong(property.getName(), property.getType().getRawClass()))
                    .forEach(property -> property.assignSerializer(ToStringSerializer.instance));
            return properties;
        }
    }

    private static final class LegacyMinorUnitStringSerializerModifier
            extends com.fasterxml.jackson.databind.ser.BeanSerializerModifier {
        @Override
        public List<com.fasterxml.jackson.databind.ser.BeanPropertyWriter> changeProperties(
                com.fasterxml.jackson.databind.SerializationConfig config,
                com.fasterxml.jackson.databind.BeanDescription beanDesc,
                List<com.fasterxml.jackson.databind.ser.BeanPropertyWriter> properties) {
            properties.stream()
                    .filter(property -> isMinorUnitLong(property.getName(), property.getType().getRawClass()))
                    .forEach(property -> property.assignSerializer(
                            com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance));
            return properties;
        }
    }
}
