package com.kinetix.payment.domain.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record MerchantWallet(
    Long id,
    String merchantPrincipalId,
    BigDecimal availableBalance,
    BigDecimal pendingEscrowBalance,
    String currency,
    Instant createdAt,
    Instant updatedAt
) implements LiabilityHolder {
    public MerchantWallet {
        if (merchantPrincipalId == null || merchantPrincipalId.isBlank()) {
            throw new IllegalArgumentException("A merchant principal id is required");
        }

        if (availableBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Available balance cannot be negative");
        }

        if (pendingEscrowBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Pending escrow balance cannot be negative");
        }
    }

    public static MerchantWallet createInitial(String merchantPrincipalId) {
        return new MerchantWallet(
            null,
            merchantPrincipalId,
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
            merchantPrincipalId,
            availableBalance,
            pendingEscrowBalance.add(EscrowAmount.moving(amount)),
            currency,
            createdAt,
            Instant.now()
        );
    }

    public MerchantWallet cancelPendingEscrow(BigDecimal amount) {
        return new MerchantWallet(
            id,
            merchantPrincipalId,
            availableBalance,
            EscrowAmount.leaving(pendingEscrowBalance, amount, "merchant " + merchantPrincipalId),
            currency,
            createdAt,
            Instant.now()
        );
    }

    public MerchantWallet releaseEscrowToAvailable(BigDecimal amount) {
        return new MerchantWallet(
            id,
            merchantPrincipalId,
            availableBalance.add(amount),
            EscrowAmount.leaving(pendingEscrowBalance, amount, "merchant " + merchantPrincipalId),
            currency,
            createdAt,
            Instant.now()
        );
    }

    @Override
    public List<LiabilityAccount> liabilities() {
        return List.of(
            new LiabilityAccount(
                LiabilityKind.MERCHANT_AVAILABLE,
                merchantPrincipalId,
                availableBalance,
                currency
            ),
            new LiabilityAccount(
                LiabilityKind.MERCHANT_PENDING_ESCROW,
                merchantPrincipalId,
                pendingEscrowBalance,
                currency
            )
        );
    }
}
