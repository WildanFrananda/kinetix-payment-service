package com.kinetix.payment.domain.entity;

import com.kinetix.payment.domain.exception.InvalidArgumentException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record DriverWallet(
    Long id,
    String driverPrincipalId,
    BigDecimal availableBalance,
    BigDecimal pendingEscrowBalance,
    String currency,
    Instant createdAt,
    Instant updatedAt
) implements LiabilityHolder {
    public DriverWallet {
        if (driverPrincipalId == null || driverPrincipalId.isBlank()) {
            throw new InvalidArgumentException("A driver principal id is required");
        }

        if (availableBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidArgumentException("Available balance cannot be negative");
        }

        if (pendingEscrowBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidArgumentException("Pending escrow balance cannot be negative");
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
            throw new InvalidArgumentException("a credit to a driver must be a positive amount");
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

    @Override
    public List<LiabilityAccount> liabilities() {
        return List.of(
            new LiabilityAccount(
                LiabilityKind.DRIVER_AVAILABLE,
                driverPrincipalId,
                availableBalance,
                currency
            ),
            new LiabilityAccount(
                LiabilityKind.DRIVER_PENDING_ESCROW,
                driverPrincipalId,
                pendingEscrowBalance,
                currency
            )
        );
    }
}
