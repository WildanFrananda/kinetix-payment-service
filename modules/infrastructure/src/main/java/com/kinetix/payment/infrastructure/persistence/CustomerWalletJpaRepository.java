package com.kinetix.payment.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface CustomerWalletJpaRepository extends JpaRepository<CustomerWalletJpaEntity, Long> {
    Optional<CustomerWalletJpaEntity> findByCustomerPrincipalId(String customerPrincipalId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM CustomerWalletJpaEntity w WHERE w.customerPrincipalId = :principalId")
    Optional<CustomerWalletJpaEntity> findByCustomerPrincipalIdForUpdate(
        @Param("principalId") String customerPrincipalId
    );
}
