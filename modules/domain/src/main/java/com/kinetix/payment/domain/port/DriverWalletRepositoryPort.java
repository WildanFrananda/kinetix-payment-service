package com.kinetix.payment.domain.port;

import com.kinetix.payment.domain.entity.DriverWallet;
import java.util.Optional;

public interface DriverWalletRepositoryPort {
    Optional<DriverWallet> findByDriverId(Long driverId);
    DriverWallet save(DriverWallet wallet);
}
