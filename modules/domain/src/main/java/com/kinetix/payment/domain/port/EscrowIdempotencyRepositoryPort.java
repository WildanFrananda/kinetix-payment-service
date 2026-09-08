package com.kinetix.payment.domain.port;

import com.kinetix.payment.domain.entity.EscrowIdempotencyRecord;
import com.kinetix.payment.domain.entity.EscrowOperation;
import java.util.Optional;

public interface EscrowIdempotencyRepositoryPort {
    Optional<EscrowIdempotencyRecord> find(EscrowOperation operation, String idempotencyKey);

    boolean existsFor(EscrowOperation operation, String orderNumber);

    EscrowIdempotencyRecord save(EscrowIdempotencyRecord record);
}
