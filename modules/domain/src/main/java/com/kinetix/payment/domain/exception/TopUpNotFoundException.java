package com.kinetix.payment.domain.exception;

public class TopUpNotFoundException extends DomainException {
    public TopUpNotFoundException(String message) {
        super(message);
    }
}
