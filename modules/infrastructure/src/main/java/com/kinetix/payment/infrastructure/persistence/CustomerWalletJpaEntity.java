package com.kinetix.payment.infrastructure.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "customer_wallets")
public class CustomerWalletJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false, unique = true)
    private Long customerId;

    @Column(name = "balance", nullable = false, precision = 12, scale = 2)
    private BigDecimal balance;

    @Column(name = "held_balance", nullable = false, precision = 12, scale = 2)
    private BigDecimal heldBalance;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public CustomerWalletJpaEntity() {}

    public CustomerWalletJpaEntity(Long id, Long customerId, BigDecimal balance, BigDecimal heldBalance, String currency, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.customerId = customerId;
        this.balance = balance;
        this.heldBalance = heldBalance;
        this.currency = currency;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public Long getCustomerId() { return customerId; }
    public BigDecimal getBalance() { return balance; }
    public BigDecimal getHeldBalance() { return heldBalance; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
