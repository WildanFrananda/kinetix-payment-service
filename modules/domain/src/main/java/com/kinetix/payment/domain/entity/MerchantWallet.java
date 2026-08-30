package com.kinetix.payment.domain.entity;

import java.math.BigDecimal;
import java.time.Instant;

public record MerchantWallet(
    Long id,
    Long merchantId,
    BigDecimal availableBalance,
    BigDecimal pendingEscrowBalance,
    String currency,
    Instant createdAt,
    Instant updatedAt
) {
    public static MerchantWallet createInitial(Long merchantId) {
        return new MerchantWallet(
            null,
            merchantId,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "IDR",
            Instant.now(),
            Instant.now()
        );
    }

    public MerchantWallet addPendingEscrow(BigDecimal amount) {
        return new MerchantWallet(
            id,
            merchantId,
            availableBalance,
            pendingEscrowBalance.add(amount),
            currency,
            createdAt,
            Instant.now()
        );
    }

    public MerchantWallet releaseEscrowToAvailable(BigDecimal amount) {
        return new MerchantWallet(
            id,
            merchantId,
            availableBalance.add(amount),
            pendingEscrowBalance.subtract(amount).max(BigDecimal.ZERO),
            currency,
            createdAt,
            Instant.now()
        );
    }
}
