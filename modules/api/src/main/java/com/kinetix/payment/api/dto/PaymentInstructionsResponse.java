package com.kinetix.payment.api.dto;

import com.kinetix.payment.domain.gateway.PaymentInstructions;

public record PaymentInstructionsResponse(
    String bank,
    String virtualAccountNumber,
    String qrCodeUrl,
    String expiresAt
) {
    public static PaymentInstructionsResponse from(PaymentInstructions instructions) {
        if (
            instructions == null || (instructions.virtualAccountNumber() == null && instructions.qrCodeUrl() == null)
        ) {
            return null;
        }
        return new PaymentInstructionsResponse(
            instructions.bank() == null ? null : instructions.bank().name(),
            instructions.virtualAccountNumber(),
            instructions.qrCodeUrl(),
            instructions.expiresAt()
        );
    }
}
