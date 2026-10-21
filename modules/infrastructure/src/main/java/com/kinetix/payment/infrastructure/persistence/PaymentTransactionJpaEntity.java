package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.entity.AttentionReason;
import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.gateway.VirtualAccountBank;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
    name = "payment_transactions",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_payment_transactions_principal_idempotency",
        columnNames = {"principal_id", "idempotency_key"}
    )
)
public class PaymentTransactionJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reference_number", nullable = false, unique = true)
    private String referenceNumber;

    @Column(name = "external_transaction_id")
    private String externalTransactionId;

    @Column(name = "principal_id", nullable = false)
    private String principalId;

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

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "bank", length = 16)
    private VirtualAccountBank bank;

    @Enumerated(EnumType.STRING)
    @Column(name = "attention_reason", length = 32)
    private AttentionReason attentionReason;

    @Column(name = "last_checked_at", insertable = false, updatable = false)
    private Instant lastCheckedAt;

    public PaymentTransactionJpaEntity() {}

    public PaymentTransactionJpaEntity(
        Long id,
        String referenceNumber,
        String externalTransactionId,
        String principalId,
        PaymentTransaction.TransactionType type,
        PaymentTransaction.PaymentMethod method,
        BigDecimal amount,
        PaymentTransaction.TransactionStatus status,
        String gatewayResponse,
        Instant createdAt,
        String idempotencyKey,
        VirtualAccountBank bank,
        AttentionReason attentionReason
    ) {
        this.id = id;
        this.referenceNumber = referenceNumber;
        this.externalTransactionId = externalTransactionId;
        this.principalId = principalId;
        this.type = type;
        this.method = method;
        this.amount = amount;
        this.status = status;
        this.gatewayResponse = gatewayResponse;
        this.createdAt = createdAt;
        this.idempotencyKey = idempotencyKey;
        this.bank = bank;
        this.attentionReason = attentionReason;
    }

    public Long getId() { return id; }
    public String getReferenceNumber() { return referenceNumber; }
    public String getExternalTransactionId() { return externalTransactionId; }
    public String getPrincipalId() { return principalId; }
    public PaymentTransaction.TransactionType getType() { return type; }
    public PaymentTransaction.PaymentMethod getMethod() { return method; }
    public BigDecimal getAmount() { return amount; }
    public PaymentTransaction.TransactionStatus getStatus() { return status; }
    public String getGatewayResponse() { return gatewayResponse; }
    public Instant getCreatedAt() { return createdAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public VirtualAccountBank getBank() { return bank; }
    public AttentionReason getAttentionReason() { return attentionReason; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
}
