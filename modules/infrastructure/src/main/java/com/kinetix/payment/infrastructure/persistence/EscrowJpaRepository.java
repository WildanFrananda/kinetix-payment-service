package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.entity.EscrowHold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EscrowJpaRepository extends JpaRepository<EscrowJpaEntity, Long> {
    Optional<EscrowJpaEntity> findByOrderNumber(String orderNumber);

    @Query("SELECT e FROM EscrowJpaEntity e WHERE e.status = :status AND e.autoReleaseAt <= :now")
    List<EscrowJpaEntity> findPendingAutoReleaseHolds(EscrowHold.EscrowStatus status, Instant now);
}
