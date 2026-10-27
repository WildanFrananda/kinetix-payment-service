package com.kinetix.payment.domain.entity;

import com.kinetix.payment.domain.exception.InsufficientBalanceException;
import com.kinetix.payment.domain.exception.InvalidArgumentException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CustomerWallet(
    Long id,
    String customerPrincipalId,
    BigDecimal balance,
    BigDecimal heldBalance,
    String currency,
    Instant createdAt,
    Instant updatedAt
) implements LiabilityHolder {
    public CustomerWallet {
        if (customerPrincipalId == null || customerPrincipalId.isBlank()) {
            throw new InvalidArgumentException("A customer principal id is required");
        }

        if (balance.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidArgumentException("Balance cannot be negative");
        }

        if (heldBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidArgumentException("Held balance cannot be negative");
        }
    }

    public static CustomerWallet createInitial(String customerPrincipalId) {
        return new CustomerWallet(
            null,
            customerPrincipalId,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "IDR",
            Instant.now(),
            Instant.now()
        );
    }

    public CustomerWallet topUp(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidArgumentException("Top-up amount must be positive");
        }

        return new CustomerWallet(
            id,
            customerPrincipalId,
            balance.add(amount),
            heldBalance,
            currency,
            createdAt,
            Instant.now()
        );
    }

    public CustomerWallet deductForCheckout(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidArgumentException("Deduction amount must be positive");
        }

        if (balance.compareTo(amount) < 0) {
            throw new InsufficientBalanceException(
                "Insufficient customer wallet balance. Required: " + amount + ", Available: " + balance
            );
        }

        return new CustomerWallet(
            id,
            customerPrincipalId,
            balance.subtract(amount),
            heldBalance,
            currency,
            createdAt,
            Instant.now()
        );
    }

    @Override
    public List<LiabilityAccount> liabilities() {
        if (heldBalance.signum() != 0) {
            throw new IllegalStateException(
                "customer " + customerPrincipalId + " has " + heldBalance + " in held_balance, which no "
                    + "payment flow writes; a snapshot that left it out would understate what is owed"
            );
        }

        return List.of(
            new LiabilityAccount(
                LiabilityKind.CUSTOMER_BALANCE,
                customerPrincipalId,
                balance,
                currency
            )
        );
    }
}
