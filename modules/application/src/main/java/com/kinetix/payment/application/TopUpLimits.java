package com.kinetix.payment.application;

import java.math.BigDecimal;

public record TopUpLimits(BigDecimal maximumTopUp, BigDecimal maximumCustomerBalance) {
    private static final BigDecimal LEDGER_CEILING = new BigDecimal("9999999999.99");

    public TopUpLimits {
        requireWholeRupiah(maximumTopUp, "the largest single top-up");
        requireWholeRupiah(maximumCustomerBalance, "the largest customer wallet balance");

        if (maximumTopUp.compareTo(TopUpService.MINIMUM_AMOUNT) < 0) {
            throw new IllegalArgumentException(
                "the largest single top-up, " + maximumTopUp.toPlainString()
                    + ", is below the smallest one, " + TopUpService.MINIMUM_AMOUNT.toPlainString()
            );
        }

        if (maximumTopUp.compareTo(maximumCustomerBalance) > 0) {
            throw new IllegalArgumentException(
                "a top-up of " + maximumTopUp.toPlainString() + " could never be credited to a wallet "
                    + "that may hold at most " + maximumCustomerBalance.toPlainString()
            );
        }
    }

    private static void requireWholeRupiah(BigDecimal value, String what) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(what + " must be a positive amount");
        }

        if (value.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(what + " must be a whole rupiah amount, not " + value.toPlainString());
        }

        if (value.compareTo(LEDGER_CEILING) > 0) {
            throw new IllegalArgumentException(
                what + ", " + value.toPlainString() + ", does not fit the ledger's numeric(12,2) columns"
            );
        }
    }
}
