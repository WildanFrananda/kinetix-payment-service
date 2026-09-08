package com.kinetix.payment.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface DriverWalletJpaRepository extends JpaRepository<DriverWalletJpaEntity, Long> {
    Optional<DriverWalletJpaEntity> findByDriverPrincipalId(String driverPrincipalId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM DriverWalletJpaEntity w WHERE w.driverPrincipalId = :principalId")
    Optional<DriverWalletJpaEntity> findByDriverPrincipalIdForUpdate(
        @Param("principalId") String driverPrincipalId);
}
