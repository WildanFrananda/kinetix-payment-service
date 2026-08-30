package com.kinetix.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MerchantWalletJpaRepository extends JpaRepository<MerchantWalletJpaEntity, Long> {
    Optional<MerchantWalletJpaEntity> findByMerchantId(Long merchantId);
}
