package com.kinetix.payment.domain.port;

import com.kinetix.payment.domain.entity.PaymentTransaction;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentTransactionRepositoryPort {
    Optional<PaymentTransaction> findByReferenceNumber(String referenceNumber);

    Optional<PaymentTransaction> findByReferenceNumberForUpdate(String referenceNumber);

    Optional<PaymentTransaction> findByPrincipalIdAndIdempotencyKey(String principalId, String idempotencyKey);

    List<PaymentTransaction> findPendingTopUpsOlderThan(Instant olderThan, int limit);

    PaymentTransaction save(PaymentTransaction transaction);
}
