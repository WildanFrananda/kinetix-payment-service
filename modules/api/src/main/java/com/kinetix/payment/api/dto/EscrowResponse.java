package com.kinetix.payment.api.dto;

import com.kinetix.payment.domain.entity.EscrowHold;
import java.math.BigDecimal;
import java.time.Instant;

public record EscrowResponse(
    Long id,
    String orderNumber,
    String customerPrincipalId,
    String merchantPrincipalId,
    String driverPrincipalId,
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
            hold.customerPrincipalId(),
            hold.merchantPrincipalId(),
            hold.driverPrincipalId(),
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
