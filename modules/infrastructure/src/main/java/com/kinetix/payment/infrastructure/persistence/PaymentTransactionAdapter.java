package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.port.PaymentTransactionRepositoryPort;
import org.springframework.stereotype.Component;
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
    public PaymentTransaction save(PaymentTransaction tx) {
        PaymentTransactionJpaEntity entity = new PaymentTransactionJpaEntity(
            tx.id(),
            tx.referenceNumber(),
            tx.externalTransactionId(),
            tx.userId(),
            tx.type(),
            tx.method(),
            tx.amount(),
            tx.status(),
            tx.gatewayResponse(),
            tx.createdAt()
        );
        PaymentTransactionJpaEntity saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    private PaymentTransaction toDomain(PaymentTransactionJpaEntity entity) {
        return new PaymentTransaction(
            entity.getId(),
            entity.getReferenceNumber(),
            entity.getExternalTransactionId(),
            entity.getUserId(),
            entity.getType(),
            entity.getMethod(),
            entity.getAmount(),
            entity.getStatus(),
            entity.getGatewayResponse(),
            entity.getCreatedAt()
        );
    }
}
