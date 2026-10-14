package com.kinetix.payment.domain.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MinorUnits {
    private static final BigDecimal PER_MAJOR = new BigDecimal("100");

    private MinorUnits() {}

    public static long of(BigDecimal amount) {
        return amount.multiply(PER_MAJOR).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }
}
