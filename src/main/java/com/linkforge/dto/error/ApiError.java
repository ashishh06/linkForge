package com.linkforge.dto.error;

import org.springframework.http.HttpStatus;

import java.time.Instant;

/**
 * The single error shape returned by every endpoint, including failures raised
 * inside the security filter chain.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path
) {

    public static ApiError of(HttpStatus status, String message, String path) {
        return new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                path
        );
    }
}
