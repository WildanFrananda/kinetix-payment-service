package com.kinetix.payment.domain.entity;

import java.math.BigDecimal;
import java.time.Instant;

public record EscrowHold(
    Long id,
    String orderNumber,
    String customerPrincipalId,
    String merchantPrincipalId,
    String driverPrincipalId,
    BigDecimal totalOrderAmount,
    BigDecimal merchantAmount,
    BigDecimal shippingFeeAmount,
    EscrowStatus status,
    Instant createdAt,
    Instant releasedAt,
    Instant shippingFeeSettledAt,
    BigDecimal goodsRefundedAmount
) {
    public EscrowHold {
        if (customerPrincipalId == null || customerPrincipalId.isBlank()) {
            throw new IllegalArgumentException("A customer principal id is required");
        }
        if (merchantPrincipalId == null || merchantPrincipalId.isBlank()) {
            throw new IllegalArgumentException("A merchant principal id is required");
        }
        if (goodsRefundedAmount == null || goodsRefundedAmount.signum() < 0) {
            throw new IllegalArgumentException("The goods refunded from a hold cannot be negative or missing");
        }
        if (merchantAmount != null && goodsRefundedAmount.compareTo(merchantAmount) > 0) {
            throw new IllegalArgumentException(
                "order " + orderNumber + " cannot have refunded " + goodsRefundedAmount
                    + " of goods when the merchant's share was " + merchantAmount
            );
        }
    }

    public enum EscrowStatus {
        HELD,
        RELEASED,
        REFUNDED,
        DISPUTED
    }

    public static EscrowHold createNewHold(
        String orderNumber,
        String customerPrincipalId,
        String merchantPrincipalId,
        String driverPrincipalId,
        BigDecimal totalOrderAmount,
        BigDecimal merchantAmount,
        BigDecimal shippingFeeAmount
    ) {
        BigDecimal parts = merchantAmount.add(shippingFeeAmount);
        if (totalOrderAmount.compareTo(parts) != 0) {
            throw new IllegalArgumentException(
                "an escrow hold for order " + orderNumber + " does not balance: the customer is charged "
                    + totalOrderAmount + " but " + merchantAmount + " is owed to the merchant and "
                    + shippingFeeAmount + " to whoever delivers, which is " + parts
            );
        }
        return new EscrowHold(
            null,
            orderNumber,
            customerPrincipalId,
            merchantPrincipalId,
            driverPrincipalId,
            totalOrderAmount,
            merchantAmount,
            shippingFeeAmount,
            EscrowStatus.HELD,
            Instant.now(),
            null,
            null,
            BigDecimal.ZERO
        );
    }

    public boolean shippingFeeSettled() {
        return shippingFeeSettledAt != null;
    }

    public BigDecimal merchantAmountOutstanding() {
        return merchantAmount.subtract(goodsRefundedAmount);
    }

    public EscrowHold refundGoods(BigDecimal amount) {
        if (status != EscrowStatus.HELD) {
            throw new IllegalStateException(
                "escrow for order " + orderNumber + " is " + status + "; goods are refunded only while it is held"
            );
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("a goods refund must be a positive amount");
        }
        if (amount.compareTo(merchantAmountOutstanding()) > 0) {
            throw new IllegalArgumentException(
                "order " + orderNumber + " still holds " + merchantAmountOutstanding()
                    + " for the merchant, which cannot cover a goods refund of " + amount
            );
        }
        return new EscrowHold(
            id,
            orderNumber,
            customerPrincipalId,
            merchantPrincipalId,
            driverPrincipalId,
            totalOrderAmount,
            merchantAmount,
            shippingFeeAmount,
            status,
            createdAt,
            releasedAt,
            shippingFeeSettledAt,
            goodsRefundedAmount.add(amount)
        );
    }

    public EscrowHold settleShippingFeeTo(String settledDriverPrincipalId) {
        if (settledDriverPrincipalId == null || settledDriverPrincipalId.isBlank()) {
            throw new IllegalArgumentException(
                "a shipping fee cannot be settled without naming the driver it is owed to"
            );
        }
        if (shippingFeeSettledAt != null) {
            throw new IllegalStateException(
                "the shipping fee for order " + orderNumber + " was already settled to "
                    + driverPrincipalId + " at " + shippingFeeSettledAt
            );
        }
        return new EscrowHold(
            id,
            orderNumber,
            customerPrincipalId,
            merchantPrincipalId,
            settledDriverPrincipalId,
            totalOrderAmount,
            merchantAmount,
            shippingFeeAmount,
            status,
            createdAt,
            releasedAt,
            Instant.now(),
            goodsRefundedAmount
        );
    }

    public EscrowHold markAsRefunded() {
        return new EscrowHold(
            id,
            orderNumber,
            customerPrincipalId,
            merchantPrincipalId,
            driverPrincipalId,
            totalOrderAmount,
            merchantAmount,
            shippingFeeAmount,
            EscrowStatus.REFUNDED,
            createdAt,
            Instant.now(),
            shippingFeeSettledAt,
            goodsRefundedAmount
        );
    }

    public EscrowHold markAsReleased() {
        return new EscrowHold(
            id,
            orderNumber,
            customerPrincipalId,
            merchantPrincipalId,
            driverPrincipalId,
            totalOrderAmount,
            merchantAmount,
            shippingFeeAmount,
            EscrowStatus.RELEASED,
            createdAt,
            Instant.now(),
            shippingFeeSettledAt,
            goodsRefundedAmount
        );
    }
}
