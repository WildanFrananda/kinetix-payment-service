package com.kinetix.payment.domain.gateway;

import java.math.BigDecimal;

public record GatewayStatus(
    GatewaySettlement settlement,
    BigDecimal grossAmount,
    String externalTransactionId,
    String gatewayResponse,
    String detail
) {}
