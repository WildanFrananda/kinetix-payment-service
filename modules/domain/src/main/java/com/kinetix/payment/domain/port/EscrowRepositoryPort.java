package com.kinetix.payment.domain.port;

import com.kinetix.payment.domain.entity.EscrowHold;
import java.util.List;
import java.util.Optional;

public interface EscrowRepositoryPort {
    Optional<EscrowHold> findByOrderNumber(String orderNumber);
    List<EscrowHold> findPendingAutoReleaseHolds();
    EscrowHold save(EscrowHold escrowHold);
}
