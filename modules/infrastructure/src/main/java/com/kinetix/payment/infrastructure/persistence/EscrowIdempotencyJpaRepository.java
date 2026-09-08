package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.entity.EscrowOperation;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EscrowIdempotencyJpaRepository extends JpaRepository<EscrowIdempotencyJpaEntity, Long> {
    Optional<EscrowIdempotencyJpaEntity> findByOperationAndIdempotencyKey(
        EscrowOperation operation, String idempotencyKey
    );

    boolean existsByOperationAndOrderNumber(EscrowOperation operation, String orderNumber);
}
