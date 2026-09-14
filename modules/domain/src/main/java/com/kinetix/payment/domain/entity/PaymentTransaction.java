package com.kinetix.payment.domain.entity;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentTransaction(
    Long id,
    String referenceNumber,
    String externalTransactionId,
    String principalId,
    TransactionType type,
    PaymentMethod method,
    BigDecimal amount,
    TransactionStatus status,
    String gatewayResponse,
    Instant createdAt,
    String idempotencyKey
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

    public static PaymentTransaction pendingTopUp(
        String referenceNumber,
        String principalId,
        String idempotencyKey,
        PaymentMethod method,
        BigDecimal amount
    ) {
        return new PaymentTransaction(
            null,
            referenceNumber,
            null,
            principalId,
            TransactionType.TOPUP,
            method,
            amount,
            TransactionStatus.PENDING,
            null,
            Instant.now(),
            idempotencyKey
        );
    }

    public PaymentTransaction withGatewayResponse(String gatewayTransactionId, String response) {
        return new PaymentTransaction(
            id,
            referenceNumber,
            gatewayTransactionId != null ? gatewayTransactionId : externalTransactionId,
            principalId,
            type,
            method,
            amount,
            status,
            response,
            createdAt,
            idempotencyKey
        );
    }

    public PaymentTransaction concludedAs(
        TransactionStatus outcome, String gatewayTransactionId, String response
    ) {
        if (status != TransactionStatus.PENDING) {
            throw new IllegalStateException(
                "transaction " + referenceNumber + " is already " + status + " and cannot become " + outcome
            );
        }
        if (outcome == TransactionStatus.PENDING) {
            throw new IllegalArgumentException("a transaction concludes as something other than pending");
        }
        return new PaymentTransaction(
            id,
            referenceNumber,
            gatewayTransactionId != null ? gatewayTransactionId : externalTransactionId,
            principalId,
            type,
            method,
            amount,
            outcome,
            response != null ? response : gatewayResponse,
            createdAt,
            idempotencyKey
        );
    }
}
