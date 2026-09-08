package com.kinetix.payment.domain.exception;

public class OrderAlreadyUnwoundException extends DomainException {
    public OrderAlreadyUnwoundException(String message) {
        super(message);
    }
}
