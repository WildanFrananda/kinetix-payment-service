package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.entity.EscrowOperation;
import com.kinetix.payment.domain.entity.IdempotencyKeySource;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
    name = "escrow_idempotency_records",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_escrow_idempotency_operation_key",
        columnNames = {"operation", "idempotency_key"}
    ),
    indexes = {
        @Index(name = "ix_escrow_idempotency_order", columnList = "order_number"),
        @Index(name = "ix_escrow_idempotency_created_at", columnList = "created_at")
    }
)
public class EscrowIdempotencyJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 32)
    private EscrowOperation operation;

    @Column(name = "idempotency_key", nullable = false, length = 300)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "key_source", nullable = false, length = 16)
    private IdempotencyKeySource keySource;

    @Column(name = "order_number", nullable = false)
    private String orderNumber;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "escrow_id")
    private Long escrowId;

    @Column(name = "detail", length = 512)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public EscrowIdempotencyJpaEntity() {}

    public EscrowIdempotencyJpaEntity(
        Long id,
        EscrowOperation operation,
        String idempotencyKey,
        IdempotencyKeySource keySource,
        String orderNumber,
        String requestFingerprint,
        Long escrowId,
        String detail,
        Instant createdAt
    ) {
        this.id = id;
        this.operation = operation;
        this.idempotencyKey = idempotencyKey;
        this.keySource = keySource;
        this.orderNumber = orderNumber;
        this.requestFingerprint = requestFingerprint;
        this.escrowId = escrowId;
        this.detail = detail;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public EscrowOperation getOperation() { return operation; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public IdempotencyKeySource getKeySource() { return keySource; }
    public String getOrderNumber() { return orderNumber; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public Long getEscrowId() { return escrowId; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
