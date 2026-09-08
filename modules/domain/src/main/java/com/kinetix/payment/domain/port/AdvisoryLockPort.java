package com.kinetix.payment.domain.port;

public interface AdvisoryLockPort {
    void lockOrder(String orderNumber);

    void lockWalletOwner(String principalId);
}
