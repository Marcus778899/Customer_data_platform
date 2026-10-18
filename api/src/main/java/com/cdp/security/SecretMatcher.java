package com.cdp.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 常數時間比對：先各自雜湊成等長，再用 MessageDigest.isEqual，不因長度或前綴洩漏時間差。 */
final class SecretMatcher {

    private SecretMatcher() {}

    static boolean matches(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(actual), sha256(expected));
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
