package com.kinetix.payment.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface MerchantWalletJpaRepository extends JpaRepository<MerchantWalletJpaEntity, Long> {
    Optional<MerchantWalletJpaEntity> findByMerchantPrincipalId(String merchantPrincipalId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM MerchantWalletJpaEntity w WHERE w.merchantPrincipalId = :principalId")
    Optional<MerchantWalletJpaEntity> findByMerchantPrincipalIdForUpdate(
        @Param("principalId") String merchantPrincipalId
    );
}
