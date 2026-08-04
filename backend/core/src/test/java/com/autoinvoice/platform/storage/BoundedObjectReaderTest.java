package com.autoinvoice.platform.storage;

import com.autoinvoice.platform.DomainException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedObjectReaderTest {
    @Test
    void acceptsObjectsAtTheLimit() throws Exception {
        assertThat(BoundedObjectReader.read(new ByteArrayInputStream(new byte[8]), 8)).hasSize(8);
    }

    @Test
    void rejectsObjectsAboveTheLimit() {
        assertThatThrownBy(() -> BoundedObjectReader.read(new ByteArrayInputStream(new byte[9]), 8))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("OBJECT_STORAGE_RESPONSE_TOO_LARGE"));
    }
}
