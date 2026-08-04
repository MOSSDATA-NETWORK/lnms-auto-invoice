package com.autoinvoice.notification;

import com.autoinvoice.platform.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Component
public class WebhookUrlPolicy {
    private final boolean allowInsecureHttp;

    public WebhookUrlPolicy(
            @Value("${auto-invoice.notification.allow-insecure-http:false}") boolean allowInsecureHttp) {
        this.allowInsecureHttp = allowInsecureHttp;
    }

    public URI validate(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException exception) {
            throw invalid("Webhook target URL is invalid");
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean permittedHttp = allowInsecureHttp && "http".equalsIgnoreCase(uri.getScheme());
        if (!(https || permittedHttp)
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw invalid("Webhook target must use HTTPS without credentials or a fragment");
        }
        if (uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65_535) {
            throw invalid("Webhook target port is invalid");
        }
        resolvePublic(uri.getHost());
        return uri.normalize();
    }

    public List<InetAddress> resolvePublic(String hostname) {
        try {
            List<InetAddress> addresses = Arrays.asList(InetAddress.getAllByName(hostname));
            if (addresses.isEmpty() || addresses.stream().anyMatch(this::isForbidden)) {
                throw forbidden(hostname);
            }
            return List.copyOf(addresses);
        } catch (UnknownHostException exception) {
            throw new DomainException("WEBHOOK_TARGET_UNRESOLVED", "Webhook target host cannot be resolved", 422,
                    Map.of("host", hostname));
        }
    }

    boolean isForbidden(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return isForbiddenIpv4(bytes);
        }
        if (address instanceof Inet6Address) {
            if ((bytes[0] & 0xfe) == 0xfc) {
                return true;
            }
            if ((bytes[0] & 0xff) == 0x20 && (bytes[1] & 0xff) == 0x01
                    && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8) {
                return true;
            }
            if (isIpv4Mapped(bytes)) {
                return isForbiddenIpv4(Arrays.copyOfRange(bytes, 12, 16));
            }
            if (isIpv4Compatible(bytes)
                    || isPrefix(bytes, new byte[]{0x20, 0x02}, 16)
                    || isPrefix(bytes, new byte[]{0x20, 0x01, 0x00, 0x00}, 32)
                    || isPrefix(bytes, new byte[]{0x00, 0x64, (byte) 0xff, (byte) 0x9b,
                            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}, 96)
                    || isPrefix(bytes, new byte[]{0x00, 0x64, (byte) 0xff, (byte) 0x9b, 0x00, 0x01}, 48)) {
                return true;
            }
        }
        return false;
    }

    private boolean isForbiddenIpv4(byte[] bytes) {
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return first == 0
                || first == 10
                || first == 127
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 0)
                || (first == 192 && second == 168)
                || (first == 192 && second == 0 && (bytes[2] & 0xff) == 2)
                || (first == 198 && (second == 18 || second == 19))
                || (first == 198 && second == 51 && (bytes[2] & 0xff) == 100)
                || (first == 203 && second == 0 && (bytes[2] & 0xff) == 113)
                || first >= 224;
    }

    private boolean isIpv4Mapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
    }

    private boolean isIpv4Compatible(byte[] bytes) {
        for (int index = 0; index < 12; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return true;
    }

    private boolean isPrefix(byte[] address, byte[] prefix, int prefixLength) {
        int completeBytes = prefixLength / 8;
        for (int index = 0; index < completeBytes; index++) {
            if (address[index] != prefix[index]) {
                return false;
            }
        }
        int remainingBits = prefixLength % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xff << (8 - remainingBits);
        return (address[completeBytes] & mask) == (prefix[completeBytes] & mask);
    }

    private DomainException invalid(String message) {
        return new DomainException("WEBHOOK_TARGET_INVALID", message, 422, Map.of());
    }

    private DomainException forbidden(String host) {
        return new DomainException("WEBHOOK_TARGET_FORBIDDEN",
                "Webhook target resolves to a private, local or reserved address", 422, Map.of("host", host));
    }
}
