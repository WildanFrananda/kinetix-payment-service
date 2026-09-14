package com.kinetix.payment.domain.exception;

public class GatewayUnavailableException extends DomainException {
    private final String referenceNumber;

    public GatewayUnavailableException(String referenceNumber, String message) {
        super(message);
        this.referenceNumber = referenceNumber;
    }

    public String referenceNumber() {
        return referenceNumber;
    }
}
