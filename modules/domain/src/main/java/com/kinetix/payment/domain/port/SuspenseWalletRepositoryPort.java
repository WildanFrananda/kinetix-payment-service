package com.kinetix.payment.domain.port;

import com.kinetix.payment.domain.entity.SuspenseWallet;
import java.util.Optional;

public interface SuspenseWalletRepositoryPort {
    Optional<SuspenseWallet> findByPurpose(String purpose);

    Optional<SuspenseWallet> findByPurposeForUpdate(String purpose);

    SuspenseWallet save(SuspenseWallet wallet);
}
