package com.autoinvoice.worker.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
final class SmtpSecurityPolicy {
    SmtpSecurityPolicy(
            @Value("${auto-invoice.worker.job-types:}") String enabledJobTypes,
            @Value("${spring.mail.properties.mail.smtp.starttls.enable:true}") boolean startTlsEnabled,
            @Value("${spring.mail.properties.mail.smtp.starttls.required:true}") boolean startTlsRequired,
            @Value("${spring.mail.properties.mail.smtp.ssl.checkserveridentity:true}") boolean checkServerIdentity,
            @Value("${auto-invoice.notification.allow-insecure-smtp:false}") boolean allowInsecureSmtp) {
        if (handlesNotifications(enabledJobTypes) && !allowInsecureSmtp
                && !(startTlsEnabled && startTlsRequired && checkServerIdentity)) {
            throw new IllegalStateException("SEND_NOTIFICATION workers require STARTTLS, mandatory TLS negotiation "
                    + "and SMTP hostname verification; use the explicit insecure development override only locally");
        }
    }

    static boolean handlesNotifications(String enabledJobTypes) {
        return enabledJobTypes == null || enabledJobTypes.isBlank()
                || Arrays.stream(enabledJobTypes.split(","))
                .map(String::trim)
                .anyMatch(SendNotificationHandler.TYPE::equals);
    }
}
