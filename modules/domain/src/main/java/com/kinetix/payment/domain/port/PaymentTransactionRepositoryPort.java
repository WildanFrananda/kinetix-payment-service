package com.kinetix.payment.domain.port;

import com.kinetix.payment.domain.entity.PaymentTransaction;
import java.util.Optional;

public interface PaymentTransactionRepositoryPort {
    Optional<PaymentTransaction> findByReferenceNumber(String referenceNumber);
    PaymentTransaction save(PaymentTransaction transaction);
}
