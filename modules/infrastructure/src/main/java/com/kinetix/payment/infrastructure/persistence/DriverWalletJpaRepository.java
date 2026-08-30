package com.kinetix.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface DriverWalletJpaRepository extends JpaRepository<DriverWalletJpaEntity, Long> {
    Optional<DriverWalletJpaEntity> findByDriverId(Long driverId);
}
