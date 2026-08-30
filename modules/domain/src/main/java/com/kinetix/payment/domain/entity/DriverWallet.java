package com.kinetix.payment.domain.entity;

import java.math.BigDecimal;
import java.time.Instant;

public record DriverWallet(
    Long id,
    Long driverId,
    BigDecimal availableBalance,
    BigDecimal pendingEscrowBalance,
    String currency,
    Instant createdAt,
    Instant updatedAt
) {
    public static DriverWallet createInitial(Long driverId) {
        return new DriverWallet(
            null,
            driverId,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "IDR",
            Instant.now(),
            Instant.now()
        );
    }

    public DriverWallet addPendingEscrow(BigDecimal amount) {
        return new DriverWallet(
            id,
            driverId,
            availableBalance,
            pendingEscrowBalance.add(amount),
            currency,
            createdAt,
            Instant.now()
        );
    }

    public DriverWallet releaseEscrowToAvailable(BigDecimal amount) {
        return new DriverWallet(
            id,
            driverId,
            availableBalance.add(amount),
            pendingEscrowBalance.subtract(amount).max(BigDecimal.ZERO),
            currency,
            createdAt,
            Instant.now()
        );
    }
}
