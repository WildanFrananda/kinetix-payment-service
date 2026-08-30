package com.kinetix.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CustomerWalletJpaRepository extends JpaRepository<CustomerWalletJpaEntity, Long> {
    Optional<CustomerWalletJpaEntity> findByCustomerId(Long customerId);
}
