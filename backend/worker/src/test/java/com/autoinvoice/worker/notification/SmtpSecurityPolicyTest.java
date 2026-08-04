package com.autoinvoice.worker.notification;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmtpSecurityPolicyTest {
    @Test
    void requiresAllTlsProtectionsForNotificationWorkers() {
        assertThatThrownBy(() -> new SmtpSecurityPolicy("SEND_NOTIFICATION", true, false, true, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hostname verification");
        assertThatThrownBy(() -> new SmtpSecurityPolicy("SEND_NOTIFICATION", true, true, false, false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SmtpSecurityPolicy("SEND_NOTIFICATION", false, true, true, false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsSecureProductionConfiguration() {
        assertThatCode(() -> new SmtpSecurityPolicy("SEND_NOTIFICATION", true, true, true, false))
                .doesNotThrowAnyException();
    }

    @Test
    void insecureOverrideIsExplicitAndDoesNotAffectOtherWorkerTypes() {
        assertThatCode(() -> new SmtpSecurityPolicy("SEND_NOTIFICATION", false, false, false, true))
                .doesNotThrowAnyException();
        assertThatCode(() -> new SmtpSecurityPolicy("IMPORT_VALIDATE", false, false, false, false))
                .doesNotThrowAnyException();
        assertThat(SmtpSecurityPolicy.handlesNotifications("")).isTrue();
    }
}
