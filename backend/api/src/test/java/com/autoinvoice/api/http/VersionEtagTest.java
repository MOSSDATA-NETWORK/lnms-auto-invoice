package com.autoinvoice.api.http;

import com.autoinvoice.platform.DomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionEtagTest {
    @Test
    void parsesStrongAndWeakNumericEntityTags() {
        assertThat(VersionEtag.parse("\"42\"")).isEqualTo(42);
        assertThat(VersionEtag.parse("W/\"7\"")).isEqualTo(7);
        assertThat(VersionEtag.format(9)).isEqualTo("\"9\"");
    }

    @Test
    void rejectsWildcardAndNonNumericEntityTags() {
        assertThatThrownBy(() -> VersionEtag.parse("*"))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).code())
                .isEqualTo("VERSION_CONFLICT");
        assertThatThrownBy(() -> VersionEtag.parse("\"abc\""))
                .isInstanceOf(DomainException.class);
    }
}
