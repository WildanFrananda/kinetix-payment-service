package com.kinetix.payment.domain.entity;

public enum LiabilityKind {
    CUSTOMER_BALANCE,
    MERCHANT_AVAILABLE,
    MERCHANT_PENDING_ESCROW,
    DRIVER_AVAILABLE,
    DRIVER_PENDING_ESCROW,
    SUSPENSE_AVAILABLE,
    SUSPENSE_PENDING_ESCROW;

    public int code() {
        return switch (this) {
            case CUSTOMER_BALANCE -> 1;
            case MERCHANT_AVAILABLE -> 2;
            case MERCHANT_PENDING_ESCROW -> 3;
            case DRIVER_AVAILABLE -> 4;
            case DRIVER_PENDING_ESCROW -> 5;
            case SUSPENSE_AVAILABLE -> 6;
            case SUSPENSE_PENDING_ESCROW -> 7;
        };
    }
}
