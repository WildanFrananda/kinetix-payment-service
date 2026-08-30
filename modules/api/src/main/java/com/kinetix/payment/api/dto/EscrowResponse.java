package com.kinetix.payment.api.dto;

import com.kinetix.payment.domain.entity.EscrowHold;
import java.math.BigDecimal;
import java.time.Instant;

public record EscrowResponse(
    Long id,
    String orderNumber,
    Long customerId,
    Long merchantId,
    Long driverId,
    BigDecimal totalOrderAmount,
    BigDecimal merchantAmount,
    BigDecimal shippingFeeAmount,
    String status,
    Instant autoReleaseAt,
    Instant createdAt,
    Instant releasedAt
) {
    public static EscrowResponse from(EscrowHold hold) {
        return new EscrowResponse(
            hold.id(),
            hold.orderNumber(),
            hold.customerId(),
            hold.merchantId(),
            hold.driverId(),
            hold.totalOrderAmount(),
            hold.merchantAmount(),
            hold.shippingFeeAmount(),
            hold.status().name(),
            hold.autoReleaseAt(),
            hold.createdAt(),
            hold.releasedAt()
        );
    }
}
