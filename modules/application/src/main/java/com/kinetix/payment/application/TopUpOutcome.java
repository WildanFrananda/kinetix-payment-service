package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.gateway.PaymentInstructions;

public record TopUpOutcome(
    PaymentTransaction transaction,
    PaymentInstructions instructions,
    boolean replayed
) {}
