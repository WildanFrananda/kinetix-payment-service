package com.kinetix.payment.application;

import java.math.BigDecimal;

public record CreateEscrowHoldCommand(
    String orderNumber,
    String customerPrincipalId,
    String merchantPrincipalId,
    String driverPrincipalId,
    BigDecimal totalOrderAmount,
    BigDecimal merchantAmount,
    BigDecimal shippingFeeAmount,
    String idempotencyKey,
    String requestFingerprint
) {}
