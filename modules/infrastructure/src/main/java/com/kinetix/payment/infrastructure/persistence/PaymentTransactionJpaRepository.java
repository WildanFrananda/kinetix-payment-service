package com.kinetix.payment.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface PaymentTransactionJpaRepository extends JpaRepository<PaymentTransactionJpaEntity, Long> {
    Optional<PaymentTransactionJpaEntity> findByReferenceNumber(String referenceNumber);

    Optional<PaymentTransactionJpaEntity> findByPrincipalIdAndIdempotencyKey(String principalId, String idempotencyKey);

    @Query("""
        SELECT t FROM PaymentTransactionJpaEntity t
        WHERE t.status = com.kinetix.payment.domain.entity.PaymentTransaction.TransactionStatus.PENDING
          AND t.type = com.kinetix.payment.domain.entity.PaymentTransaction.TransactionType.TOPUP
          AND t.createdAt < :olderThan
        ORDER BY t.createdAt ASC
        """)
    List<PaymentTransactionJpaEntity> findPendingTopUpsOlderThan(
        @Param("olderThan") Instant olderThan, Limit limit
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM PaymentTransactionJpaEntity t WHERE t.referenceNumber = :referenceNumber")
    Optional<PaymentTransactionJpaEntity> findByReferenceNumberForUpdate(
        @Param("referenceNumber") String referenceNumber
    );
}
