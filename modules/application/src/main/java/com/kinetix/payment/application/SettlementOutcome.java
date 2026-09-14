package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.PaymentTransaction;

public record SettlementOutcome(PaymentTransaction transaction, boolean credited) {}
