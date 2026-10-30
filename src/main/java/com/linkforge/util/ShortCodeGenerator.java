package com.linkforge.util;

import com.linkforge.config.properties.LinkforgeProperties;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates random Base62 short codes (decision D1).
 *
 * <p>Randomness is deliberate: a counter-based scheme would be faster but enumerable.
 * The database unique constraint is the real guarantee against duplicates; the
 * caller retries when it rejects an insert.
 */
@Component
public class ShortCodeGenerator {

    private final SecureRandom random = new SecureRandom();
    private final int length;

    public ShortCodeGenerator(LinkforgeProperties properties) {
        this.length = properties.link().codeLength();
    }

    public String generate() {
        return Base62.random(length, random);
    }

    public int length() {
        return length;
    }
}
