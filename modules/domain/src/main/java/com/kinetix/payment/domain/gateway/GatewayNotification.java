package com.kinetix.payment.domain.gateway;

public record GatewayNotification(
    String orderId,
    String statusCode,
    String grossAmount,
    String signatureKey
) {}
