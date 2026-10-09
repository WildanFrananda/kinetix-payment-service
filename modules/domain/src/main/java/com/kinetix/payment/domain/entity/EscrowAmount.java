package com.kinetix.payment.domain.entity;

import java.math.BigDecimal;

final class EscrowAmount {
    private EscrowAmount() {}

    static BigDecimal moving(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("an escrow movement cannot be negative or missing: " + amount);
        }
        return amount;
    }

    static BigDecimal leaving(BigDecimal pending, BigDecimal amount, String holder) {
        moving(amount);
        if (pending.compareTo(amount) < 0) {
            throw new IllegalStateException(
                holder + " holds " + pending + " in escrow, which cannot cover " + amount
            );
        }
        return pending.subtract(amount);
    }
}
