package com.sqlteacher.server;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Shared hashing, token and constant-time comparison primitives for the cloud
 * server and its store family. Text is UTF-8 encoded before hashing; hex output
 * is lowercase. Token generation matches the historical cloud token format:
 * 32 random bytes as unpadded base64url. Password hashing keeps the deployed
 * PBKDF2-HMAC-SHA256 parameters (310k iterations, 256-bit output).
 */
final class Hashes {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final int PBKDF2_ITERATIONS = 310_000;
    private static final int PBKDF2_BITS = 256;

    private Hashes() { }

    static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256Bytes(value));
    }

    static byte[] sha256Bytes(String value) {
        Objects.requireNonNull(value, "value must not be null");
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    static boolean constantTimeEquals(byte[] left, byte[] right) {
        return MessageDigest.isEqual(left, right);
    }

    static String randomToken() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(TOKEN_BYTES));
    }

    /** Uniformly random zero-padded decimal code of the requested length (mailed verification codes). */
    static String randomNumericCode(int digits) {
        if (digits < 1 || digits > 9) throw new IllegalArgumentException("digits must be 1 to 9");
        StringBuilder code = new StringBuilder(digits);
        for (int index = 0; index < digits; index++) code.append(RANDOM.nextInt(10));
        return code.toString();
    }

    /** Unambiguous uppercase alphabet (no 0/O/1/I) for shareable one-time codes. */
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

    /** Uniformly random code from the unambiguous uppercase alphabet (one-time role grant codes). */
    static String randomCode(int length) {
        if (length < 1 || length > 64) throw new IllegalArgumentException("length must be 1 to 64");
        StringBuilder code = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    static byte[] randomBytes(int count) {
        byte[] value = new byte[count];
        RANDOM.nextBytes(value);
        return value;
    }

    static byte[] pbkdf2Hash(char[] password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, PBKDF2_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Password hashing unavailable", error);
        }
    }
}
