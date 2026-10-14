package com.kinetix.payment.infrastructure.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "liability_snapshot_records")
public class LiabilitySnapshotRecordJpaEntity {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "as_of", nullable = false, updatable = false)
    private Instant asOf;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "account_count", nullable = false, updatable = false)
    private long accountCount;

    @Column(name = "total", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal total;

    @Column(name = "digest", nullable = false, updatable = false)
    private byte[] digest;

    public LiabilitySnapshotRecordJpaEntity() {}

    public LiabilitySnapshotRecordJpaEntity(
        UUID id,
        Instant asOf,
        String currency,
        long accountCount,
        BigDecimal total,
        byte[] digest
    ) {
        this.id = id;
        this.asOf = asOf;
        this.currency = currency;
        this.accountCount = accountCount;
        this.total = total;
        this.digest = digest.clone();
    }

    public UUID getId() { return id; }
    public Instant getAsOf() { return asOf; }
    public String getCurrency() { return currency; }
    public long getAccountCount() { return accountCount; }
    public BigDecimal getTotal() { return total; }
    public byte[] getDigest() { return digest.clone(); }
}
