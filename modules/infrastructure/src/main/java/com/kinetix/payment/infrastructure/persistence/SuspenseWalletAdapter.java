package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.entity.SuspenseWallet;
import com.kinetix.payment.domain.port.SuspenseWalletRepositoryPort;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
public class SuspenseWalletAdapter implements SuspenseWalletRepositoryPort {
    private final SuspenseWalletJpaRepository jpaRepository;

    public SuspenseWalletAdapter(SuspenseWalletJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<SuspenseWallet> findByPurpose(String purpose) {
        return jpaRepository.findByPurpose(purpose).map(this::toDomain);
    }

    @Override
    public Optional<SuspenseWallet> findByPurposeForUpdate(String purpose) {
        return jpaRepository.findByPurposeForUpdate(purpose).map(this::toDomain);
    }

    @Override
    public SuspenseWallet save(SuspenseWallet wallet) {
        SuspenseWalletJpaEntity entity = new SuspenseWalletJpaEntity(
            wallet.id(),
            wallet.purpose(),
            wallet.availableBalance(),
            wallet.pendingEscrowBalance(),
            wallet.currency(),
            wallet.createdAt(),
            wallet.updatedAt()
        );
        return toDomain(jpaRepository.save(entity));
    }

    private SuspenseWallet toDomain(SuspenseWalletJpaEntity entity) {
        return new SuspenseWallet(
            entity.getId(),
            entity.getPurpose(),
            entity.getAvailableBalance(),
            entity.getPendingEscrowBalance(),
            entity.getCurrency(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
