package com.autoinvoice.platform.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

@Service
public class SecretCipher {
    private static final String PREFIX = "v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final byte[] masterKey;
    private final SecureRandom secureRandom;

    @Autowired
    public SecretCipher(@Value("${auto-invoice.security.master-key-base64:}") String masterKeyBase64) {
        this(decodeKey(masterKeyBase64), new SecureRandom());
    }

    SecretCipher(byte[] masterKey, SecureRandom secureRandom) {
        if (masterKey == null || masterKey.length != 32) {
            throw new IllegalStateException("AUTO_INVOICE_MASTER_KEY_BASE64 must decode to exactly 32 bytes");
        }
        this.masterKey = masterKey.clone();
        this.secureRandom = java.util.Objects.requireNonNull(secureRandom, "secureRandom");
    }

    public String encrypt(String plaintext, UUID tenantId, String purpose) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("Secret plaintext must not be blank");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(tenantId, purpose));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt secret", exception);
        }
    }

    public String decrypt(String encoded, UUID tenantId, String purpose) {
        if (encoded == null || !encoded.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Secret ciphertext has an unsupported format");
        }
        byte[] combined;
        try {
            combined = Base64.getUrlDecoder().decode(encoded.substring(PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Secret ciphertext is not valid Base64", exception);
        }
        if (combined.length <= NONCE_BYTES + 16) {
            throw new IllegalArgumentException("Secret ciphertext is truncated");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        byte[] ciphertext = new byte[combined.length - NONCE_BYTES];
        System.arraycopy(combined, 0, nonce, 0, nonce.length);
        System.arraycopy(combined, nonce.length, ciphertext, 0, ciphertext.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(tenantId, purpose));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("Secret ciphertext failed authentication", exception);
        }
    }

    private byte[] aad(UUID tenantId, String purpose) {
        if (tenantId == null || purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("Secret tenant and purpose are required");
        }
        return (tenantId + "\n" + purpose).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] decodeKey(String value) {
        if (value == null || value.isBlank()) {
            return new byte[0];
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("AUTO_INVOICE_MASTER_KEY_BASE64 is not valid Base64", exception);
        }
    }
}
