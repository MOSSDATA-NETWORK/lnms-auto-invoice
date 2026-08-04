package com.autoinvoice.notification;

import com.autoinvoice.platform.DomainException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookUrlPolicyTest {
    private final WebhookUrlPolicy policy = new WebhookUrlPolicy(false);

    @Test
    void blocksLoopbackPrivateLinkLocalAndCloudMetadataAddresses() throws Exception {
        for (String address : new String[]{"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.8",
                "169.254.169.254", "100.64.0.1", "::1", "fc00::1", "fe80::1"}) {
            assertThat(policy.isForbidden(InetAddress.getByName(address))).as(address).isTrue();
        }
    }

    @Test
    void permitsPublicAddresses() throws Exception {
        assertThat(policy.isForbidden(InetAddress.getByName("1.1.1.1"))).isFalse();
        assertThat(policy.isForbidden(InetAddress.getByName("2606:4700:4700::1111"))).isFalse();
    }

    @Test
    void blocksIpv6TransitionAndTranslationRanges() throws Exception {
        for (String address : new String[]{"2002:0101:0101::1", "2001:0000:4136:e378::1",
                "64:ff9b::c000:0201", "64:ff9b:1::c000:0201", "::c000:0201"}) {
            assertThat(policy.isForbidden(InetAddress.getByName(address))).as(address).isTrue();
        }
    }

    @Test
    void rejectsCredentialsAndUnsupportedSchemes() {
        assertThatThrownBy(() -> policy.validate("file:///etc/passwd"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> policy.validate("https://user:secret@example.com/hook"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void requiresHttpsAndRejectsPortZeroByDefault() {
        assertThatThrownBy(() -> policy.validate("http://1.1.1.1/hook"))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).code())
                .isEqualTo("WEBHOOK_TARGET_INVALID");
        assertThatThrownBy(() -> policy.validate("https://1.1.1.1:0/hook"))
                .isInstanceOf(DomainException.class)
                .extracting(exception -> ((DomainException) exception).code())
                .isEqualTo("WEBHOOK_TARGET_INVALID");
    }

    @Test
    void permitsHttpOnlyWhenExplicitlyEnabled() {
        assertThat(new WebhookUrlPolicy(true).validate("http://1.1.1.1/hook").getScheme())
                .isEqualTo("http");
    }
}
