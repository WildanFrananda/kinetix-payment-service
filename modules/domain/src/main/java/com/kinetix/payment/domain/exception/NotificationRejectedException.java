package com.kinetix.payment.domain.exception;

public class NotificationRejectedException extends DomainException {
    public NotificationRejectedException(String message) {
        super(message);
    }
}
