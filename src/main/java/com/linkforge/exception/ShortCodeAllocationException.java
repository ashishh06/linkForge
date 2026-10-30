package com.linkforge.exception;

import org.springframework.http.HttpStatus;

/**
 * Every generated code collided with an existing row. With 62^7 codes this should be
 * effectively impossible; surfacing a 500 is honest about a system problem rather
 * than silently retrying forever.
 */
public class ShortCodeAllocationException extends ApiException {

    public ShortCodeAllocationException(int attempts) {
        super(HttpStatus.INTERNAL_SERVER_ERROR,
                "Could not allocate a unique short code after " + attempts + " attempts");
    }
}
