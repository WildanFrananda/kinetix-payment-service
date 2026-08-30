package com.kinetix.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PaymentTransactionJpaRepository extends JpaRepository<PaymentTransactionJpaEntity, Long> {
    Optional<PaymentTransactionJpaEntity> findByReferenceNumber(String referenceNumber);
}
