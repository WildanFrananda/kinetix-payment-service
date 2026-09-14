package com.kinetix.payment.domain.gateway;

public record GatewayCharge(
    GatewayChargeOutcome outcome,
    String externalTransactionId,
    PaymentInstructions instructions,
    String gatewayResponse,
    String detail
) {}
