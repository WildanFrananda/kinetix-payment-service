package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.gateway.VirtualAccountBank;
import java.math.BigDecimal;

public record TopUpCommand(
    String customerPrincipalId,
    String idempotencyKey,
    BigDecimal amount,
    PaymentTransaction.PaymentMethod method,
    VirtualAccountBank bank
) {}
