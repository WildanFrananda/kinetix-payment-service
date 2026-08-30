package com.kinetix.payment.domain.exception;

public class EscrowNotFoundException extends DomainException {
    public EscrowNotFoundException(String message) {
        super(message);
    }
}
