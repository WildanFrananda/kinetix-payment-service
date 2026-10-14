package com.kinetix.payment.domain.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

public record LiabilitySnapshotRecord(
    UUID id,
    Instant asOf,
    String currency,
    long accountCount,
    BigDecimal total,
    String digest
) {
    private static final Pattern ISO_CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");

    public LiabilitySnapshotRecord {
        if (id == null) {
            throw new IllegalArgumentException("a liability snapshot record names the snapshot it records");
        }

        if (asOf == null) {
            throw new IllegalArgumentException("the record of snapshot " + id + " says when it was taken");
        }

        if (currency == null || !ISO_CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("the record of snapshot " + id + " has no ISO 4217 currency");
        }

        if (accountCount < 0) {
            throw new IllegalArgumentException("snapshot " + id + " cannot hold " + accountCount + " accounts");
        }

        if (total == null || total.signum() < 0) {
            throw new IllegalArgumentException("snapshot " + id + " cannot owe " + total);
        }

        if (digest == null || !SHA_256_HEX.matcher(digest).matches()) {
            throw new IllegalArgumentException("the digest of snapshot " + id + " is not 32 bytes of lowercase hex");
        }
    }
}
