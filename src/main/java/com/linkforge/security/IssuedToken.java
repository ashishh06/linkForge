package com.linkforge.security;

import java.time.Instant;

public record IssuedToken(
        String value,
        long expiresInSeconds,
        Instant expiresAt
) {
}
