package com.kinetix.payment.domain.exception;

public class DuplicateIdempotencyKeyException extends DomainException {
    public DuplicateIdempotencyKeyException(String message) {
        super(message);
    }
}
