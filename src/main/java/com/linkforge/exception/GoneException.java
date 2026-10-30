package com.linkforge.exception;

import org.springframework.http.HttpStatus;

/**
 * The resource exists but is no longer usable: expired, disabled or soft-deleted
 * links (FR-7).
 */
public class GoneException extends ApiException {

    public GoneException(String message) {
        super(HttpStatus.GONE, message);
    }
}
