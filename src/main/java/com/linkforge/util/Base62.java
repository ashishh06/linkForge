package com.linkforge.util;

import java.security.SecureRandom;

/**
 * Base62 encoding and random code generation.
 *
 * <p>The alphabet is ordered so that its first 10 characters are the decimal digits
 * and the remaining 52 are the ASCII letters. Codes are <strong>case-sensitive</strong>
 * (the database charset check agrees), which gives 62<sup>7</sup> ≈ 3.5 × 10<sup>12</sup>
 * possible 7-character codes.
 */
public final class Base62 {

    public static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private static final int BASE = ALPHABET.length();

    private Base62() {
    }

    /**
     * Encodes a non-negative value using the Base62 alphabet.
     */
    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative");
        }
        if (value == 0) {
            return String.valueOf(ALPHABET.charAt(0));
        }

        StringBuilder encoded = new StringBuilder(11);
        long remaining = value;
        while (remaining > 0) {
            encoded.append(ALPHABET.charAt((int) (remaining % BASE)));
            remaining /= BASE;
        }
        return encoded.reverse().toString();
    }

    /**
     * Returns a cryptographically random code of the requested length.
     *
     * <p>{@link SecureRandom} is thread-safe, so one instance may be shared by the
     * whole application.
     */
    public static String random(int length, SecureRandom random) {
        if (length < 1) {
            throw new IllegalArgumentException("length must be at least 1");
        }

        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(ALPHABET.charAt(random.nextInt(BASE)));
        }
        return code.toString();
    }

    /**
     * Returns whether every character of {@code value} belongs to the Base62 alphabet.
     */
    public static boolean isBase62(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (ALPHABET.indexOf(value.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }
}
