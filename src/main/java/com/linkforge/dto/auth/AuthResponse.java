package com.linkforge.dto.auth;

/**
 * Phase 1 issues access tokens only. Phase 2 adds the refresh token and its
 * rotation metadata to this response.
 */
public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UserResponse user
) {
}
