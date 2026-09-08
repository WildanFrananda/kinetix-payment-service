package com.kinetix.payment.domain.exception;

public class IdempotencyConflictException extends DomainException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
