package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.entity.MerchantWallet;
import com.kinetix.payment.domain.port.MerchantWalletRepositoryPort;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
public class MerchantWalletAdapter implements MerchantWalletRepositoryPort {
    private final MerchantWalletJpaRepository jpaRepository;

    public MerchantWalletAdapter(MerchantWalletJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<MerchantWallet> findByMerchantId(Long merchantId) {
        return jpaRepository.findByMerchantId(merchantId)
            .map(this::toDomain);
    }

    @Override
    public MerchantWallet save(MerchantWallet wallet) {
        MerchantWalletJpaEntity entity = new MerchantWalletJpaEntity(
            wallet.id(),
            wallet.merchantId(),
            wallet.availableBalance(),
            wallet.pendingEscrowBalance(),
            wallet.currency(),
            wallet.createdAt(),
            wallet.updatedAt()
        );
        MerchantWalletJpaEntity saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    private MerchantWallet toDomain(MerchantWalletJpaEntity entity) {
        return new MerchantWallet(
            entity.getId(),
            entity.getMerchantId(),
            entity.getAvailableBalance(),
            entity.getPendingEscrowBalance(),
            entity.getCurrency(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
