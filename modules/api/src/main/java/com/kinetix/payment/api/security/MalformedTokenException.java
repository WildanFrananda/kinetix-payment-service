package com.kinetix.payment.api.security;

public class MalformedTokenException extends RuntimeException {
    public MalformedTokenException(String message) {
        super(message);
    }
}
