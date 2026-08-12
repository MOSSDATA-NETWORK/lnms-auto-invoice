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
    void emptyConfigurationAllowsSelfServiceRegistration() {
        LibrenmsOriginPolicy policy = new LibrenmsOriginPolicy("  ");

        assertThat(policy.requireAllowed("https://librenms.example").toString())
                .isEqualTo("https://librenms.example");
        assertThat(policy.requireAllowed("http://192.168.10.20:8080/").toString())
                .isEqualTo("http://192.168.10.20:8080");
    }

    @Test
    void infrastructureAddressesAreAlwaysRejected() {
        for (String origins : new String[]{"", "https://librenms.example"}) {
            LibrenmsOriginPolicy policy = new LibrenmsOriginPolicy(origins);
            for (String origin : new String[]{
                    "http://localhost", "http://127.0.0.1:9000", "http://169.254.169.254",
                    "http://0.0.0.0", "http://[::1]", "http://[fe80::1]"
            }) {
                assertThatThrownBy(() -> policy.requireAllowed(origin)).as(origin)
                        .isInstanceOfSatisfying(DomainException.class,
                                exception -> assertThat(exception.code())
                                        .isEqualTo("LIBRENMS_ORIGIN_NOT_ALLOWED"));
            }
        }
    }

    @Test
    void invalidConfiguredOriginFailsApplicationConfiguration() {
        assertThatThrownBy(() -> new LibrenmsOriginPolicy("https://librenms.example/api"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LIBRENMS_ALLOWED_ORIGINS");
    }
}
