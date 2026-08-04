package com.autoinvoice.api.security;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.OptionalLong;

@Service
public class TotpService {
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private final Clock clock;

    public TotpService() {
        this(Clock.systemUTC());
    }

    TotpService(Clock clock) {
        this.clock = clock;
    }

    public boolean verify(String base32Secret, String code) {
        return matchCounter(base32Secret, code, Long.MIN_VALUE).isPresent();
    }

    public OptionalLong matchCounter(String base32Secret, String code, long lastAcceptedCounter) {
        if (base32Secret == null || code == null || !code.matches("\\d{6}")) {
            return OptionalLong.empty();
        }
        long counter = clock.instant().getEpochSecond() / 30;
        for (long offset = 1; offset >= -1; offset--) {
            long candidateCounter = counter + offset;
            if (candidateCounter > lastAcceptedCounter
                    && generate(base32Secret, candidateCounter).equals(code)) {
                return OptionalLong.of(candidateCounter);
            }
        }
        return OptionalLong.empty();
    }

    private String generate(String secret, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decodeBase32(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return "%06d".formatted(binary % 1_000_000);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate TOTP", exception);
        }
    }

    private byte[] decodeBase32(String input) {
        String normalized = input.replace("=", "").replace(" ", "").toUpperCase();
        ByteBuffer output = ByteBuffer.allocate(normalized.length() * 5 / 8 + 1);
        int buffer = 0;
        int bitsLeft = 0;
        for (char value : normalized.toCharArray()) {
            int index = BASE32.indexOf(value);
            if (index < 0) {
                throw new IllegalArgumentException("Invalid Base32 secret");
            }
            buffer = (buffer << 5) | index;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                output.put((byte) (buffer >> (bitsLeft - 8)));
                bitsLeft -= 8;
            }
        }
        byte[] bytes = new byte[output.position()];
        output.flip();
        output.get(bytes);
        return bytes;
    }
}
