package com.kinetix.payment.domain.port;

import com.kinetix.payment.domain.entity.EscrowHold;
import java.util.Optional;

public interface EscrowRepositoryPort {
    Optional<EscrowHold> findByOrderNumber(String orderNumber);

    Optional<EscrowHold> findByOrderNumberForUpdate(String orderNumber);

    EscrowHold save(EscrowHold escrowHold);
}
