package com.linkforge.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for failures that map to a specific HTTP status.
 *
 * <p>Services throw these; {@link GlobalExceptionHandler} renders them in the one
 * documented error shape.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public ApiException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
