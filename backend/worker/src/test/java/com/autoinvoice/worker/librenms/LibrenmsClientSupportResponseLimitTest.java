package com.autoinvoice.worker.librenms;

import com.autoinvoice.platform.DomainException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibrenmsClientSupportResponseLimitTest {
    @Test
    void rejectsDeclaredResponsesAboveTheLimitBeforeReading() {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[]{1});

        assertThatThrownBy(() -> LibrenmsClientSupport.readLimited(input, 9, 8))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("LIBRENMS_RESPONSE_TOO_LARGE"));
        assertThat(input.available()).isEqualTo(1);
    }

    @Test
    void rejectsChunkedResponsesThatCrossTheLimit() {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[9]);

        assertThatThrownBy(() -> LibrenmsClientSupport.readLimited(input, -1, 8))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("LIBRENMS_RESPONSE_TOO_LARGE"));
    }

    @Test
    void acceptsAResponseAtTheExactLimit() throws Exception {
        assertThat(LibrenmsClientSupport.readLimited(new ByteArrayInputStream(new byte[8]), -1, 8))
                .hasSize(8);
    }
}
