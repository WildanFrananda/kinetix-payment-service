package com.kinetix.payment.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LiabilitySnapshotTotals(
    UUID snapshotId,
    Instant asOf,
    String currency,
    long accountCount,
    BigDecimal total,
    String digest
) {}
