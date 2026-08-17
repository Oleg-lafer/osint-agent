package com.leadspotnic.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 helpers shared across the codebase: a hex digest (used as prompt-cache keys) and a
 * positive long derived from the first 8 bytes (used as a content id for posts).
 */
public final class Hashing {

    private Hashing() {
    }

    /** Full SHA-256 of the text as a lowercase hex string. */
    public static String sha256Hex(String text) {
        byte[] hash = digest(text);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /** First 8 bytes of the SHA-256, as a positive long — a stable id derived from content. */
    public static long sha256Long(String text) {
        byte[] hash = digest(text);
        long id = 0;
        for (int i = 0; i < 8; i++) {
            id = (id << 8) | (hash[i] & 0xff);
        }
        return id >>> 1;   // drop the sign bit so the id is always positive
    }

    private static byte[] digest(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
