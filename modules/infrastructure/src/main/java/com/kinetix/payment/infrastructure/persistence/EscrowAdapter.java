package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.entity.EscrowHold;
import com.kinetix.payment.domain.port.EscrowRepositoryPort;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class EscrowAdapter implements EscrowRepositoryPort {
    private final EscrowJpaRepository jpaRepository;

    public EscrowAdapter(EscrowJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<EscrowHold> findByOrderNumber(String orderNumber) {
        return jpaRepository.findByOrderNumber(orderNumber)
            .map(this::toDomain);
    }

    @Override
    public List<EscrowHold> findPendingAutoReleaseHolds() {
        return jpaRepository.findPendingAutoReleaseHolds(EscrowHold.EscrowStatus.HELD, Instant.now())
            .stream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public EscrowHold save(EscrowHold hold) {
        EscrowJpaEntity entity = new EscrowJpaEntity(
            hold.id(),
            hold.orderNumber(),
            hold.customerId(),
            hold.merchantId(),
            hold.driverId(),
            hold.totalOrderAmount(),
            hold.merchantAmount(),
            hold.shippingFeeAmount(),
            hold.status(),
            hold.autoReleaseAt(),
            hold.createdAt(),
            hold.releasedAt()
        );
        EscrowJpaEntity saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    private EscrowHold toDomain(EscrowJpaEntity entity) {
        return new EscrowHold(
            entity.getId(),
            entity.getOrderNumber(),
            entity.getCustomerId(),
            entity.getMerchantId(),
            entity.getDriverId(),
            entity.getTotalOrderAmount(),
            entity.getMerchantAmount(),
            entity.getShippingFeeAmount(),
            entity.getStatus(),
            entity.getAutoReleaseAt(),
            entity.getCreatedAt(),
            entity.getReleasedAt()
        );
    }
}
