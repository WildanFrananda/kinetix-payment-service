package com.kinetix.payment.infrastructure.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "suspense_wallets")
public class SuspenseWalletJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "purpose", nullable = false, unique = true, length = 64)
    private String purpose;

    @Column(name = "available_balance", nullable = false, precision = 12, scale = 2)
    private BigDecimal availableBalance;

    @Column(name = "pending_escrow_balance", nullable = false, precision = 12, scale = 2)
    private BigDecimal pendingEscrowBalance;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public SuspenseWalletJpaEntity() {}

    public SuspenseWalletJpaEntity(Long id, String purpose, BigDecimal availableBalance, BigDecimal pendingEscrowBalance, String currency, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.purpose = purpose;
        this.availableBalance = availableBalance;
        this.pendingEscrowBalance = pendingEscrowBalance;
        this.currency = currency;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public String getPurpose() { return purpose; }
    public BigDecimal getAvailableBalance() { return availableBalance; }
    public BigDecimal getPendingEscrowBalance() { return pendingEscrowBalance; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
