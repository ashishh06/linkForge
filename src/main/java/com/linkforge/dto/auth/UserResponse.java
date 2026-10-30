package com.linkforge.dto.auth;

public record UserResponse(
        Long id,
        String email,
        String role
) {
}
