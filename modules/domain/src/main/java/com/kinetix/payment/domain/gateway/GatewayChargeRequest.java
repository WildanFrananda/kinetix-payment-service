package com.kinetix.payment.domain.gateway;

import com.kinetix.payment.domain.entity.PaymentTransaction;

public record GatewayChargeRequest(
    String referenceNumber,
    long grossAmountRupiah,
    PaymentTransaction.PaymentMethod method,
    VirtualAccountBank bank
) {}
