package com.linkforge.dto.link;

import java.time.Instant;

public record LinkResponse(
        Long id,
        String code,
        String shortUrl,
        String originalUrl,
        boolean customAlias,
        Instant createdAt,
        Instant expiresAt
) {
}
