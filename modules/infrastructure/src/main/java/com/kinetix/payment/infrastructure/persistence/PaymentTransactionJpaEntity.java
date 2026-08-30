package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.entity.PaymentTransaction;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payment_transactions")
public class PaymentTransactionJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reference_number", nullable = false, unique = true)
    private String referenceNumber;

    @Column(name = "external_transaction_id")
    private String externalTransactionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private PaymentTransaction.TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 32)
    private PaymentTransaction.PaymentMethod method;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentTransaction.TransactionStatus status;

    @Column(name = "gateway_response", columnDefinition = "TEXT")
    private String gatewayResponse;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public PaymentTransactionJpaEntity() {}

    public PaymentTransactionJpaEntity(Long id, String referenceNumber, String externalTransactionId, Long userId, PaymentTransaction.TransactionType type, PaymentTransaction.PaymentMethod method, BigDecimal amount, PaymentTransaction.TransactionStatus status, String gatewayResponse, Instant createdAt) {
        this.id = id;
        this.referenceNumber = referenceNumber;
        this.externalTransactionId = externalTransactionId;
        this.userId = userId;
        this.type = type;
        this.method = method;
        this.amount = amount;
        this.status = status;
        this.gatewayResponse = gatewayResponse;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getReferenceNumber() { return referenceNumber; }
    public String getExternalTransactionId() { return externalTransactionId; }
    public Long getUserId() { return userId; }
    public PaymentTransaction.TransactionType getType() { return type; }
    public PaymentTransaction.PaymentMethod getMethod() { return method; }
    public BigDecimal getAmount() { return amount; }
    public PaymentTransaction.TransactionStatus getStatus() { return status; }
    public String getGatewayResponse() { return gatewayResponse; }
    public Instant getCreatedAt() { return createdAt; }
}
