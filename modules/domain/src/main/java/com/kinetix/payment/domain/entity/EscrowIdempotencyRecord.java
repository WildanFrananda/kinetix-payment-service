package com.kinetix.payment.domain.entity;

import java.time.Instant;

public record EscrowIdempotencyRecord(
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
    public EscrowIdempotencyRecord {
        if (operation == null) {
            throw new IllegalArgumentException("An escrow operation is required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("An idempotency key is required");
        }
        if (orderNumber == null || orderNumber.isBlank()) {
            throw new IllegalArgumentException("An order number is required");
        }
        if (requestFingerprint == null || requestFingerprint.isBlank()) {
            throw new IllegalArgumentException("A request fingerprint is required");
        }
    }

    public static EscrowIdempotencyRecord opening(
        EscrowOperation operation,
        String idempotencyKey,
        IdempotencyKeySource keySource,
        String orderNumber,
        String requestFingerprint,
        String detail
    ) {
        return new EscrowIdempotencyRecord(
            null, operation, idempotencyKey, keySource, orderNumber, requestFingerprint,
            null, detail, Instant.now()
        );
    }

    public EscrowIdempotencyRecord appliedTo(Long resolvedEscrowId) {
        return new EscrowIdempotencyRecord(
            id, operation, idempotencyKey, keySource, orderNumber, requestFingerprint,
            resolvedEscrowId, detail, createdAt
        );
    }

    public boolean isTombstone() {
        return operation == EscrowOperation.REFUND && escrowId == null;
    }
}
