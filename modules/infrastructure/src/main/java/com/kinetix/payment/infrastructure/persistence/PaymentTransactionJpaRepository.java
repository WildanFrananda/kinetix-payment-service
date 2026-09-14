package com.kinetix.payment.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface PaymentTransactionJpaRepository extends JpaRepository<PaymentTransactionJpaEntity, Long> {
    Optional<PaymentTransactionJpaEntity> findByReferenceNumber(String referenceNumber);

    Optional<PaymentTransactionJpaEntity> findByPrincipalIdAndIdempotencyKey(String principalId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM PaymentTransactionJpaEntity t WHERE t.referenceNumber = :referenceNumber")
    Optional<PaymentTransactionJpaEntity> findByReferenceNumberForUpdate(
        @Param("referenceNumber") String referenceNumber
    );
}
