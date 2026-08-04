package com.autoinvoice.worker.librenms;

import com.autoinvoice.platform.DomainException;
import com.autoinvoice.usage.LibrenmsOriginPolicy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibrenmsClientSupportOriginPolicyTest {
    @Test
    void everyConnectionUsesTheCanonicalDeploymentOrigin() {
        LibrenmsClientSupport support = support("https://librenms.example");

        assertThat(support.allowedBaseUrl("HTTPS://LIBRENMS.EXAMPLE:443/"))
                .isEqualTo("https://librenms.example");
    }

    @Test
    void workerRejectsAStoredOriginAfterAllowlistConfigurationDrifts() {
        LibrenmsClientSupport support = support("https://replacement.example");

        assertThatThrownBy(() -> support.allowedBaseUrl("https://librenms.example"))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo("LIBRENMS_ORIGIN_NOT_ALLOWED"));
    }

    private LibrenmsClientSupport support(String allowedOrigins) {
        return new LibrenmsClientSupport(null, null, new LibrenmsOriginPolicy(allowedOrigins));
    }
}
