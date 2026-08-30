package com.kinetix.payment.domain.entity;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentTransaction(
    Long id,
    String referenceNumber,
    String externalTransactionId,
    Long userId,
    TransactionType type,
    PaymentMethod method,
    BigDecimal amount,
    TransactionStatus status,
    String gatewayResponse,
    Instant createdAt
) {
    public enum TransactionType {
        TOPUP,
        CHECKOUT_PAYMENT,
        ESCROW_RELEASE,
        REFUND
    }

    public enum PaymentMethod {
        INTERNAL_WALLET,
        MIDTRANS_VA,
        MIDTRANS_QRIS,
        MOCK_SANDBOX
    }

    public enum TransactionStatus {
        PENDING,
        SUCCESS,
        FAILED,
        EXPIRED
    }
}
