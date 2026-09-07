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
    Instant releasedAt
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
            null
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
            Instant.now()
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
            Instant.now()
        );
    }
}
