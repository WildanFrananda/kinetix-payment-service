package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.port.PaymentTransactionRepositoryPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class PaymentTransactionAdapter implements PaymentTransactionRepositoryPort {
    private final PaymentTransactionJpaRepository jpaRepository;

    public PaymentTransactionAdapter(PaymentTransactionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<PaymentTransaction> findByReferenceNumber(String referenceNumber) {
        return jpaRepository.findByReferenceNumber(referenceNumber)
            .map(this::toDomain);
    }

    @Override
    public Optional<PaymentTransaction> findByReferenceNumberForUpdate(String referenceNumber) {
        return jpaRepository.findByReferenceNumberForUpdate(referenceNumber)
            .map(this::toDomain);
    }

    @Override
    public Optional<PaymentTransaction> findByPrincipalIdAndIdempotencyKey(String principalId, String idempotencyKey) {
        return jpaRepository.findByPrincipalIdAndIdempotencyKey(principalId, idempotencyKey)
            .map(this::toDomain);
    }

    @Override
    public List<PaymentTransaction> findPendingTopUpsOlderThan(Instant olderThan, int limit) {
        return jpaRepository.findPendingTopUpsOlderThan(olderThan, Limit.of(limit)).stream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public PaymentTransaction save(PaymentTransaction tx) {
        PaymentTransactionJpaEntity entity = new PaymentTransactionJpaEntity(
            tx.id(),
            tx.referenceNumber(),
            tx.externalTransactionId(),
            tx.principalId(),
            tx.type(),
            tx.method(),
            tx.amount(),
            tx.status(),
            tx.gatewayResponse(),
            tx.createdAt(),
            tx.idempotencyKey()
        );
        try {
            return toDomain(jpaRepository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException violation) {
            throw ConstraintViolationTranslator.translate(violation);
        }
    }

    private PaymentTransaction toDomain(PaymentTransactionJpaEntity entity) {
        return new PaymentTransaction(
            entity.getId(),
            entity.getReferenceNumber(),
            entity.getExternalTransactionId(),
            entity.getPrincipalId(),
            entity.getType(),
            entity.getMethod(),
            entity.getAmount(),
            entity.getStatus(),
            entity.getGatewayResponse(),
            entity.getCreatedAt(),
            entity.getIdempotencyKey()
        );
    }
}
