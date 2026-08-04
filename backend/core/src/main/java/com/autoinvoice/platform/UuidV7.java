package com.autoinvoice.platform;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

public final class UuidV7 {
    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        return generate(Clock.systemUTC());
    }

    static UUID generate(Clock clock) {
        long timestamp = clock.millis() & 0x0000FFFFFFFFFFFFL;
        long randomA = RANDOM.nextLong() & 0x0FFFL;
        long randomB = RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL;
        long most = (timestamp << 16) | 0x7000L | randomA;
        long least = 0x8000000000000000L | randomB;
        return new UUID(most, least);
    }
}

