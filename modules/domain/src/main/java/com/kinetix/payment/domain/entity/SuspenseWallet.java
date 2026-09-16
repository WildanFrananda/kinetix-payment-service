package com.kinetix.payment.domain.entity;

import java.math.BigDecimal;
import java.time.Instant;

public record SuspenseWallet(
    Long id,
    String purpose,
    BigDecimal availableBalance,
    BigDecimal pendingEscrowBalance,
    String currency,
    Instant createdAt,
    Instant updatedAt
) {
    public static final String UNASSIGNED_DRIVER_SHIPPING_FEE = "UNASSIGNED_DRIVER_SHIPPING_FEE";

    public SuspenseWallet {
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("A suspense purpose is required");
        }
        if (availableBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Available balance cannot be negative");
        }
        if (pendingEscrowBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Pending escrow balance cannot be negative");
        }
    }

    public static SuspenseWallet createInitial(String purpose) {
        return new SuspenseWallet(
            null,
            purpose,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "IDR",
            Instant.now(),
            Instant.now()
        );
    }

    public SuspenseWallet addPendingEscrow(BigDecimal amount) {
        return new SuspenseWallet(
            id,
            purpose,
            availableBalance,
            pendingEscrowBalance.add(amount),
            currency,
            createdAt,
            Instant.now()
        );
    }

    public SuspenseWallet cancelPendingEscrow(BigDecimal amount) {
        return new SuspenseWallet(
            id,
            purpose,
            availableBalance,
            pendingEscrowBalance.subtract(amount).max(BigDecimal.ZERO),
            currency,
            createdAt,
            Instant.now()
        );
    }

    public SuspenseWallet releaseEscrowToAvailable(BigDecimal amount) {
        return new SuspenseWallet(
            id,
            purpose,
            availableBalance.add(amount),
            pendingEscrowBalance.subtract(amount).max(BigDecimal.ZERO),
            currency,
            createdAt,
            Instant.now()
        );
    }
}
