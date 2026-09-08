package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.entity.EscrowIdempotencyRecord;
import com.kinetix.payment.domain.entity.EscrowOperation;
import com.kinetix.payment.domain.port.EscrowIdempotencyRepositoryPort;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class EscrowIdempotencyAdapter implements EscrowIdempotencyRepositoryPort {
    private final EscrowIdempotencyJpaRepository jpaRepository;

    public EscrowIdempotencyAdapter(EscrowIdempotencyJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<EscrowIdempotencyRecord> find(EscrowOperation operation, String idempotencyKey) {
        return jpaRepository.findByOperationAndIdempotencyKey(operation, idempotencyKey)
            .map(this::toDomain);
    }

    @Override
    public boolean existsFor(EscrowOperation operation, String orderNumber) {
        return jpaRepository.existsByOperationAndOrderNumber(operation, orderNumber);
    }

    @Override
    public EscrowIdempotencyRecord save(EscrowIdempotencyRecord record) {
        EscrowIdempotencyJpaEntity entity = new EscrowIdempotencyJpaEntity(
            record.id(),
            record.operation(),
            record.idempotencyKey(),
            record.keySource(),
            record.orderNumber(),
            record.requestFingerprint(),
            record.escrowId(),
            record.detail(),
            record.createdAt()
        );
        try {
            return toDomain(jpaRepository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException violation) {
            throw ConstraintViolationTranslator.translate(violation);
        }
    }

    private EscrowIdempotencyRecord toDomain(EscrowIdempotencyJpaEntity entity) {
        return new EscrowIdempotencyRecord(
            entity.getId(),
            entity.getOperation(),
            entity.getIdempotencyKey(),
            entity.getKeySource(),
            entity.getOrderNumber(),
            entity.getRequestFingerprint(),
            entity.getEscrowId(),
            entity.getDetail(),
            entity.getCreatedAt()
        );
    }
}
