package com.approvalengine.application;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The engine's two hashing needs: digesting values it must not store in the clear, and
 * signing outbound webhooks.
 */
public final class Hashing {

    private static final String UTF8 = "UTF-8";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Hashing() {
    }

    /** Digest used for API keys and idempotency request bodies. */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(bytes(value)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; its absence is not a runtime condition.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Signs a webhook body so the consumer can verify the engine sent it.
     *
     * <p>Signs the <b>raw body bytes</b>, which is why the outbox stores the exact payload it
     * will POST rather than re-serializing at send time: the consumer verifies the same bytes
     * it received, and re-serialization could change key order and silently break every
     * signature.
     */
    public static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(bytes(secret), "HmacSHA256"));
            return toHex(mac.doFinal(bytes(payload)));
        } catch (Exception e) {
            throw new IllegalStateException("failed to compute HMAC-SHA256", e);
        }
    }

    /**
     * Constant-time comparison for signature verification.
     *
     * <p>Not needed by the engine itself — it signs rather than verifies — but included
     * because the consumer-side contract is documented from here, and a comparison that
     * short-circuits on the first differing byte leaks the expected signature a byte at a
     * time.
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        byte[] left = bytes(a);
        byte[] right = bytes(b);
        if (left.length != right.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < left.length; i++) {
            diff |= left[i] ^ right[i];
        }
        return diff == 0;
    }

    private static byte[] bytes(String value) {
        try {
            return (value == null ? "" : value).getBytes(UTF8);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unavailable", e);
        }
    }

    private static String toHex(byte[] data) {
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
