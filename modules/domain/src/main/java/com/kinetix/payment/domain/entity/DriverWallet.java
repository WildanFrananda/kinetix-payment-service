package com.kinetix.payment.domain.entity;

import java.math.BigDecimal;
import java.time.Instant;

public record DriverWallet(
    Long id,
    String driverPrincipalId,
    BigDecimal availableBalance,
    BigDecimal pendingEscrowBalance,
    String currency,
    Instant createdAt,
    Instant updatedAt
) {
    public DriverWallet {
        if (driverPrincipalId == null || driverPrincipalId.isBlank()) {
            throw new IllegalArgumentException("A driver principal id is required");
        }
        if (availableBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Available balance cannot be negative");
        }
        if (pendingEscrowBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Pending escrow balance cannot be negative");
        }
    }

    public static DriverWallet createInitial(String driverPrincipalId) {
        return new DriverWallet(
            null,
            driverPrincipalId,
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
            driverPrincipalId,
            availableBalance,
            pendingEscrowBalance.add(EscrowAmount.moving(amount)),
            currency,
            createdAt,
            Instant.now()
        );
    }

    public DriverWallet cancelPendingEscrow(BigDecimal amount) {
        return new DriverWallet(
            id,
            driverPrincipalId,
            availableBalance,
            EscrowAmount.leaving(pendingEscrowBalance, amount, "driver " + driverPrincipalId),
            currency,
            createdAt,
            Instant.now()
        );
    }

    public DriverWallet creditAvailable(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("a credit to a driver must be a positive amount");
        }
        return new DriverWallet(
            id,
            driverPrincipalId,
            availableBalance.add(amount),
            pendingEscrowBalance,
            currency,
            createdAt,
            Instant.now()
        );
    }

    public DriverWallet releaseEscrowToAvailable(BigDecimal amount) {
        return new DriverWallet(
            id,
            driverPrincipalId,
            availableBalance.add(amount),
            EscrowAmount.leaving(pendingEscrowBalance, amount, "driver " + driverPrincipalId),
            currency,
            createdAt,
            Instant.now()
        );
    }
}
