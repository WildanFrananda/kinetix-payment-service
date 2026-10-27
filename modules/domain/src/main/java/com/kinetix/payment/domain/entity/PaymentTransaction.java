package com.kinetix.payment.domain.entity;

import com.kinetix.payment.domain.exception.InvalidArgumentException;
import com.kinetix.payment.domain.gateway.VirtualAccountBank;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
    String idempotencyKey,
    VirtualAccountBank bank,
    AttentionReason attentionReason
) {
    private static final int LEDGER_SCALE = 2;

    public enum TransactionType {
        TOPUP,
        CHECKOUT_PAYMENT,
        ESCROW_RELEASE,
        REFUND,
        SHIPPING_FEE
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
        EXPIRED,
        NEEDS_ATTENTION
    }

    public static PaymentTransaction pendingTopUp(
        String referenceNumber,
        String principalId,
        String idempotencyKey,
        PaymentMethod method,
        VirtualAccountBank bank,
        BigDecimal amount
    ) {
        return new PaymentTransaction(
            null,
            referenceNumber,
            null,
            principalId,
            TransactionType.TOPUP,
            method,
            amount.setScale(LEDGER_SCALE, RoundingMode.UNNECESSARY),
            TransactionStatus.PENDING,
            null,
            Instant.now(),
            idempotencyKey,
            bank,
            null
        );
    }

    public boolean hasGatewayAnswer() {
        return gatewayResponse != null;
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
            idempotencyKey,
            bank,
            attentionReason
        );
    }

    public PaymentTransaction concludedAs(
        TransactionStatus outcome,
        String gatewayTransactionId,
        String response
    ) {
        requirePending(outcome);

        if (outcome == TransactionStatus.PENDING) {
            throw new InvalidArgumentException("a transaction concludes as something other than pending");
        }

        if (outcome == TransactionStatus.NEEDS_ATTENTION) {
            throw new InvalidArgumentException("a transaction is set aside for attention with a reason");
        }

        return concluded(outcome, null, gatewayTransactionId, response);
    }

    public PaymentTransaction setAsideFor(
        AttentionReason reason,
        String gatewayTransactionId,
        String response
    ) {
        requirePending(TransactionStatus.NEEDS_ATTENTION);

        if (reason == null) {
            throw new InvalidArgumentException("a transaction set aside for attention needs a reason");
        }

        return concluded(TransactionStatus.NEEDS_ATTENTION, reason, gatewayTransactionId, response);
    }

    private void requirePending(TransactionStatus next) {
        if (status != TransactionStatus.PENDING) {
            throw new IllegalStateException(
                "transaction " + referenceNumber + " is already " + status + " and cannot become " + next
            );
        }
    }

    private PaymentTransaction concluded(
        TransactionStatus outcome,
        AttentionReason reason,
        String gatewayTransactionId,
        String response
    ) {
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
            idempotencyKey,
            bank,
            reason
        );
    }
}
