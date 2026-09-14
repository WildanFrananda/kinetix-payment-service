package com.kinetix.payment.domain.gateway;

public record PaymentInstructions(
    VirtualAccountBank bank,
    String virtualAccountNumber,
    String qrCodeUrl,
    String expiresAt
) {
    public static PaymentInstructions none() {
        return new PaymentInstructions(null, null, null, null);
    }
}
