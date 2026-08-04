package com.autoinvoice.platform.storage;

import com.autoinvoice.platform.DomainException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public final class BoundedObjectReader {
    public static final int DEFAULT_MAXIMUM_BYTES = 64 * 1024 * 1024;

    private BoundedObjectReader() {
    }

    public static byte[] read(InputStream input) throws IOException {
        return read(input, DEFAULT_MAXIMUM_BYTES);
    }

    static byte[] read(InputStream input, int maximumBytes) throws IOException {
        byte[] bytes = input.readNBytes(maximumBytes + 1);
        if (bytes.length > maximumBytes) {
            throw new DomainException("OBJECT_STORAGE_RESPONSE_TOO_LARGE",
                    "Stored object exceeded the configured safety limit", 502,
                    Map.of("maximum_bytes", maximumBytes));
        }
        return bytes;
    }
}
