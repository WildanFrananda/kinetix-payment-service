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
    Instant autoReleaseAt,
    Instant createdAt,
    Instant releasedAt,
    Instant shippingFeeSettledAt
) {
    public EscrowHold {
        if (customerPrincipalId == null || customerPrincipalId.isBlank()) {
            throw new IllegalArgumentException("A customer principal id is required");
        }
        if (merchantPrincipalId == null || merchantPrincipalId.isBlank()) {
            throw new IllegalArgumentException("A merchant principal id is required");
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
            Instant.now().plusSeconds(48 * 3600),
            Instant.now(),
            null,
            null
        );
    }

    public boolean shippingFeeSettled() {
        return shippingFeeSettledAt != null;
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
            autoReleaseAt,
            createdAt,
            releasedAt,
            Instant.now()
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
            autoReleaseAt,
            createdAt,
            Instant.now(),
            shippingFeeSettledAt
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
            autoReleaseAt,
            createdAt,
            Instant.now(),
            shippingFeeSettledAt
        );
    }
}
