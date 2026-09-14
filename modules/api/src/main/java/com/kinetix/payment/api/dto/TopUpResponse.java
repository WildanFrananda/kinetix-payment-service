package com.kinetix.payment.api.dto;

import com.kinetix.payment.application.TopUpOutcome;
import com.kinetix.payment.domain.entity.PaymentTransaction;
import java.math.BigDecimal;

public record TopUpResponse(
    String referenceNumber,
    String status,
    BigDecimal amount,
    String paymentMethod,
    PaymentInstructionsResponse instructions
) {
    public static TopUpResponse from(TopUpOutcome outcome) {
        PaymentTransaction transaction = outcome.transaction();
        return new TopUpResponse(
            transaction.referenceNumber(),
            transaction.status().name(),
            transaction.amount(),
            transaction.method().name(),
            PaymentInstructionsResponse.from(outcome.instructions())
        );
    }
}
