package com.kinetix.payment.application;

import java.math.BigDecimal;
import java.time.Instant;

public record LiabilitySnapshotTotals(
    Instant asOf,
    String currency,
    long accountCount,
    BigDecimal total
) {}
