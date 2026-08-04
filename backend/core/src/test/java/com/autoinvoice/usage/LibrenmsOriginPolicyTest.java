package com.autoinvoice.usage;

import com.autoinvoice.platform.DomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibrenmsOriginPolicyTest {
    @Test
    void canonicalizesOriginsAndDefaultPortsBeforeExactMatching() {
        LibrenmsOriginPolicy policy = new LibrenmsOriginPolicy(
                " HTTPS://LibreNMS.EXAMPLE:443/ , http://collector.example:8080 ");

        assertThat(policy.requireAllowed("https://librenms.example").toString())
                .isEqualTo("https://librenms.example");
        assertThat(policy.requireAllowed("https://LIBRENMS.example.:443/").toString())
                .isEqualTo("https://librenms.example");
        assertThat(policy.requireAllowed("http://collector.example:8080/").toString())
                .isEqualTo("http://collector.example:8080");
    }

    @Test
    void requiresAnExactSchemeHostAndPortMatch() {
        LibrenmsOriginPolicy policy = new LibrenmsOriginPolicy("https://librenms.example:8443");

        for (String origin : new String[]{
                "http://librenms.example:8443",
                "https://librenms.example",
                "https://librenms.example:9443",
                "https://sub.librenms.example:8443"
        }) {
            assertThatThrownBy(() -> policy.requireAllowed(origin)).as(origin)
                    .isInstanceOfSatisfying(DomainException.class,
                            exception -> assertThat(exception.code()).isEqualTo("LIBRENMS_ORIGIN_NOT_ALLOWED"));
        }
    }

    @Test
    void rejectsCredentialsQueryFragmentAndNonRootPaths() {
        LibrenmsOriginPolicy policy = new LibrenmsOriginPolicy("https://librenms.example");

        for (String origin : new String[]{
                "https://user:secret@librenms.example",
                "https://librenms.example?target=internal",
                "https://librenms.example#fragment",
                "https://librenms.example/api",
                "file:///etc/passwd"
        }) {
            assertThatThrownBy(() -> policy.requireAllowed(origin)).as(origin)
                    .isInstanceOfSatisfying(DomainException.class,
                            exception -> assertThat(exception.code()).isEqualTo("LIBRENMS_ORIGIN_INVALID"));
        }
    }

    @Test
    void emptyConfigurationFailsClosed() {
        LibrenmsOriginPolicy policy = new LibrenmsOriginPolicy("  ");

        assertThatThrownBy(() -> policy.requireAllowed("https://librenms.example"))
                .isInstanceOfSatisfying(DomainException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("LIBRENMS_ORIGINS_NOT_CONFIGURED");
                    assertThat(exception.status()).isEqualTo(503);
                });
    }

    @Test
    void invalidConfiguredOriginFailsApplicationConfiguration() {
        assertThatThrownBy(() -> new LibrenmsOriginPolicy("https://librenms.example/api"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LIBRENMS_ALLOWED_ORIGINS");
    }
}
