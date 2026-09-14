package com.kinetix.payment.domain.gateway;

public enum GatewaySettlement {
    SETTLED,
    PENDING,
    FAILED,
    EXPIRED,
    NOT_FOUND,
    UNKNOWN
}
