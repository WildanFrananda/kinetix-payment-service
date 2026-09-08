package com.kinetix.payment.domain.port;

import com.kinetix.payment.domain.entity.DriverWallet;
import java.util.Optional;

public interface DriverWalletRepositoryPort {
    Optional<DriverWallet> findByDriverPrincipalId(String driverPrincipalId);

    Optional<DriverWallet> findByDriverPrincipalIdForUpdate(String driverPrincipalId);

    DriverWallet save(DriverWallet wallet);
}
