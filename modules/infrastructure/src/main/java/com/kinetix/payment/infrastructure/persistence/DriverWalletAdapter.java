package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.entity.DriverWallet;
import com.kinetix.payment.domain.port.DriverWalletRepositoryPort;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
public class DriverWalletAdapter implements DriverWalletRepositoryPort {
    private final DriverWalletJpaRepository jpaRepository;

    public DriverWalletAdapter(DriverWalletJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<DriverWallet> findByDriverId(Long driverId) {
        return jpaRepository.findByDriverId(driverId)
            .map(this::toDomain);
    }

    @Override
    public DriverWallet save(DriverWallet wallet) {
        DriverWalletJpaEntity entity = new DriverWalletJpaEntity(
            wallet.id(),
            wallet.driverId(),
            wallet.availableBalance(),
            wallet.pendingEscrowBalance(),
            wallet.currency(),
            wallet.createdAt(),
            wallet.updatedAt()
        );
        DriverWalletJpaEntity saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    private DriverWallet toDomain(DriverWalletJpaEntity entity) {
        return new DriverWallet(
            entity.getId(),
            entity.getDriverId(),
            entity.getAvailableBalance(),
            entity.getPendingEscrowBalance(),
            entity.getCurrency(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
