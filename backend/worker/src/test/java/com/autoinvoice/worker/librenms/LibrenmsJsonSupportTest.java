package com.autoinvoice.worker.librenms;

import com.autoinvoice.platform.DomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibrenmsJsonSupportTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LibrenmsJsonSupport support = new LibrenmsJsonSupport();

    @Test
    void selectsOnlyTheExactHalfOpenHistoryPeriod() throws Exception {
        var root = objectMapper.readTree("""
                {"bill_history":[
                  {"bill_hist_id":10,"bill_datefrom":"2026-06-01 00:00:00","bill_dateto":"2026-07-01 00:00:00","bill_peak_in":100,"bill_peak_out":200},
                  {"bill_hist_id":11,"bill_datefrom":"2026-07-01 00:00:00","bill_dateto":"2026-08-01 00:00:00","bill_peak_in":300,"bill_peak_out":400}
                ]}
                """);

        var selected = support.exactHistory(root,
                OffsetDateTime.parse("2026-07-01T00:00:00+08:00"),
                OffsetDateTime.parse("2026-08-01T00:00:00+08:00"), ZoneId.of("Asia/Shanghai"));

        assertThat(support.requiredLong(selected, "bill_hist_id")).isEqualTo(11);
    }

    @Test
    void aggregateRequiresLibreNmsFinalValueAndNeverAddsDirectional95thValues() {
        assertThatThrownBy(() -> LibrenmsHistorySyncHandler.selectRate("AGGREGATE", 100L, 200L, null))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("aggregate 95th");
        assertThat(LibrenmsHistorySyncHandler.selectRate("AGGREGATE", 100L, 200L, 250L))
                .isEqualTo(250L);
    }

    @Test
    void missingCriticalFieldIsNotConvertedToZero() throws Exception {
        var node = objectMapper.readTree("{\"bill_hist_id\":12}");

        assertThatThrownBy(() -> support.requiredLong(node, "bill_peak_in"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("missing");
    }
}
