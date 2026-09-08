package com.kinetix.payment.domain.exception;

public class DuplicateOrderNumberException extends DomainException {
    public DuplicateOrderNumberException(String message) {
        super(message);
    }
}
