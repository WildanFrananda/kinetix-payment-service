package com.kinetix.payment.domain.entity;

import java.math.BigDecimal;
import java.time.Instant;

public record EscrowHold(
    Long id,
    String orderNumber,
    Long customerId,
    Long merchantId,
    Long driverId,
    BigDecimal totalOrderAmount,
    BigDecimal merchantAmount,
    BigDecimal shippingFeeAmount,
    EscrowStatus status,
    Instant autoReleaseAt,
    Instant createdAt,
    Instant releasedAt
) {
    public enum EscrowStatus {
        HELD,
        RELEASED,
        REFUNDED,
        DISPUTED
    }

    public static EscrowHold createNewHold(
        String orderNumber,
        Long customerId,
        Long merchantId,
        Long driverId,
        BigDecimal totalOrderAmount,
        BigDecimal merchantAmount,
        BigDecimal shippingFeeAmount
    ) {
        return new EscrowHold(
            null,
            orderNumber,
            customerId,
            merchantId,
            driverId,
            totalOrderAmount,
            merchantAmount,
            shippingFeeAmount,
            EscrowStatus.HELD,
            Instant.now().plusSeconds(48 * 3600),
            Instant.now(),
            null
        );
    }

    public EscrowHold markAsReleased() {
        return new EscrowHold(
            id,
            orderNumber,
            customerId,
            merchantId,
            driverId,
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
