package com.autoinvoice.worker.notification;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationDeliveryPolicyTest {
    @Test
    void interruptedSmtpAttemptsAreNeverAutomaticallySentAgain() {
        assertThat(NotificationDeliveryPolicy.emailAction("SENDING"))
                .isEqualTo(NotificationDeliveryPolicy.EmailAction.MARK_UNCERTAIN);
        assertThat(NotificationDeliveryPolicy.emailAction("UNCERTAIN"))
                .isEqualTo(NotificationDeliveryPolicy.EmailAction.RETURN_UNCERTAIN);
    }

    @Test
    void onlyKnownPreSendFailuresRemainRetryable() {
        assertThat(NotificationDeliveryPolicy.emailAction("PENDING"))
                .isEqualTo(NotificationDeliveryPolicy.EmailAction.SEND);
        assertThat(NotificationDeliveryPolicy.emailAction("RETRY"))
                .isEqualTo(NotificationDeliveryPolicy.EmailAction.SEND);
        assertThat(NotificationDeliveryPolicy.emailAction("DEAD"))
                .isEqualTo(NotificationDeliveryPolicy.EmailAction.SEND);
        assertThat(NotificationDeliveryPolicy.emailAction("CANCELLED"))
                .isEqualTo(NotificationDeliveryPolicy.EmailAction.REJECT);
    }
}
