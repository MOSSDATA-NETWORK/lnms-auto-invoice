package com.autoinvoice.api.librenms;

import com.autoinvoice.platform.DomainException;
import com.autoinvoice.usage.LibrenmsOriginPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibrenmsControllerOriginPolicyTest {
    @Test
    void createPathPersistsOnlyTheCanonicalAllowlistedOrigin() {
        LibrenmsController controller = controller("https://librenms.example");

        assertThat(controller.allowedBaseUrl("HTTPS://LIBRENMS.EXAMPLE:443/"))
                .isEqualTo("https://librenms.example");
    }

    @Test
    void createPathRejectsAnOriginOutsideTheDeploymentAllowlist() {
        LibrenmsController controller = controller("https://librenms.example");

        assertThatThrownBy(() -> controller.allowedBaseUrl("http://169.254.169.254"))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("LIBRENMS_ORIGIN_NOT_ALLOWED"));
    }

    private LibrenmsController controller(String allowedOrigins) {
        return new LibrenmsController(null, null, null, null, null, null,
                new LibrenmsOriginPolicy(allowedOrigins));
    }
}
