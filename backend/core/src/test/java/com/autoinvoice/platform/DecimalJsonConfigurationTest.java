package com.autoinvoice.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DecimalJsonConfigurationTest {
    @Test
    void serializesDecimalAsLosslessString() throws Exception {
        ObjectMapper mapper = new DecimalJsonConfiguration().legacyObjectMapper();

        assertThat(mapper.writeValueAsString(new Value(new BigDecimal("9007199254740993.123400"))))
                .isEqualTo("{\"amount\":\"9007199254740993.123400\"}");
    }

    @Test
    void configuresHttpMapperToSerializeDecimalAsLosslessString() throws Exception {
        tools.jackson.databind.ObjectMapper mapper = JsonMapper.builder()
                .addModule(new DecimalJsonConfiguration().decimalStringJsonModule())
                .build();

        assertThat(mapper.writeValueAsString(new Value(new BigDecimal("9007199254740993.123400"))))
                .isEqualTo("{\"amount\":\"9007199254740993.123400\"}");
    }

    @Test
    void serializesMinorUnitLongsAsStringsWithoutChangingVersions() throws Exception {
        ObjectMapper legacyMapper = new DecimalJsonConfiguration().legacyObjectMapper()
                .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        tools.jackson.databind.ObjectMapper httpMapper = JsonMapper.builder()
                .propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
                .addModule(new DecimalJsonConfiguration().decimalStringJsonModule())
                .build();
        MinorValue value = new MinorValue(9_007_199_254_740_993L, 2L);

        assertThat(legacyMapper.writeValueAsString(value))
                .isEqualTo("{\"amount_minor\":\"9007199254740993\",\"version\":2}");
        assertThat(httpMapper.writeValueAsString(value))
                .isEqualTo("{\"amount_minor\":\"9007199254740993\",\"version\":2}");
    }

    @Test
    void acceptsMinorUnitStringsAtTheHttpBoundary() throws Exception {
        tools.jackson.databind.ObjectMapper mapper = JsonMapper.builder()
                .propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
                .addModule(new DecimalJsonConfiguration().decimalStringJsonModule())
                .build();

        MinorValue value = mapper.readValue(
                "{\"amount_minor\":\"9007199254740993\",\"version\":2}", MinorValue.class);

        assertThat(value.amountMinor()).isEqualTo(9_007_199_254_740_993L);
        assertThat(value.version()).isEqualTo(2L);
    }

    private record Value(BigDecimal amount) {
    }

    private record MinorValue(long amountMinor, long version) {
    }
}
