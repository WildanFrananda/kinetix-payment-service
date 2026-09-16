package com.kinetix.payment.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface SuspenseWalletJpaRepository extends JpaRepository<SuspenseWalletJpaEntity, Long> {
    Optional<SuspenseWalletJpaEntity> findByPurpose(String purpose);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM SuspenseWalletJpaEntity w WHERE w.purpose = :purpose")
    Optional<SuspenseWalletJpaEntity> findByPurposeForUpdate(@Param("purpose") String purpose);
}
