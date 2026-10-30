package com.linkforge.exception;

import org.springframework.http.HttpStatus;

/**
 * Bad credentials or a missing/invalid token. The message is deliberately generic:
 * it must not reveal whether an email address is registered.
 */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
