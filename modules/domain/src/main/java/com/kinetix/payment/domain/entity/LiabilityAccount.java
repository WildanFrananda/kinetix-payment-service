package com.kinetix.payment.domain.entity;

import com.kinetix.payment.domain.exception.InvalidArgumentException;
import java.math.BigDecimal;

public record LiabilityAccount(
    LiabilityKind kind,
    String holder,
    BigDecimal balance,
    String currency
) {
    public LiabilityAccount {
        if (kind == null) {
            throw new InvalidArgumentException("a liability names what kind of balance it is");
        }

        if (holder == null || holder.isBlank()) {
            throw new InvalidArgumentException("a " + kind + " liability names who it is owed to");
        }

        if (balance == null || balance.signum() < 0) {
            throw new InvalidArgumentException(
                "the " + kind + " owed to " + holder + " is " + balance + ", and a liability is never negative"
            );
        }

        if (currency == null || currency.isBlank()) {
            throw new InvalidArgumentException("the " + kind + " owed to " + holder + " has no currency");
        }
    }

    public boolean ownedByPrincipal() {
        return switch (kind) {
            case SUSPENSE_AVAILABLE, SUSPENSE_PENDING_ESCROW -> false;
            default -> true;
        };
    }
}
