package com.kinetix.payment.domain.exception;

public class InvalidArgumentException extends IllegalArgumentException {
    public InvalidArgumentException(String message) {
        super(message);
    }

    public InvalidArgumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
