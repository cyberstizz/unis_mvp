package com.unis.service.mfa;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts authenticator secrets before they're stored (AES-256-GCM).
 *
 * The key is the Railway variable UNIS_MFA_KEY: 32 random bytes, base64.
 * Make one with:   openssl rand -base64 32
 *
 * Keep that key in your password manager. If it's lost, every admin simply
 * sets up their authenticator again (see the SQL file for the reset), so
 * losing it locks nobody out for good.
 *
 * Stored form: "v1:" + base64(12-byte IV + ciphertext + 16-byte tag).
 */
public final class MfaCrypto {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    /** @throws IllegalStateException when the key is missing or not 32 bytes. */
    public MfaCrypto(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("UNIS_MFA_KEY is not set");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("UNIS_MFA_KEY is not valid base64");
        }
        if (raw.length != 32) {
            throw new IllegalStateException("UNIS_MFA_KEY must be 32 bytes (make it with: openssl rand -base64 32)");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain);
            return PREFIX + Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt the authenticator secret", e);
        }
    }

    public byte[] decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Unrecognised authenticator secret format");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Wrong key (UNIS_MFA_KEY changed) or tampered data.
            throw new IllegalStateException("Could not decrypt the authenticator secret", e);
        }
    }
}
