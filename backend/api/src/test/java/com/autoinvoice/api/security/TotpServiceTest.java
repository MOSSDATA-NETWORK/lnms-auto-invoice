package com.autoinvoice.api.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class TotpServiceTest {
    @Test
    void verifiesRfc6238VectorWithinWindow() {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(59), ZoneOffset.UTC);
        TotpService service = new TotpService(clock);

        assertThat(service.verify("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "287082")).isTrue();
        assertThat(service.verify("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "287083")).isFalse();
    }

    @Test
    void returnsTheAcceptedCounterAndRejectsAReplayOfTheSameTimeStep() {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(59), ZoneOffset.UTC);
        TotpService service = new TotpService(clock);

        OptionalLong accepted = service.matchCounter(
                "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "287082", -1);

        assertThat(accepted).hasValue(1);
        assertThat(service.matchCounter(
                "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "287082", accepted.getAsLong())).isEmpty();
    }
}
