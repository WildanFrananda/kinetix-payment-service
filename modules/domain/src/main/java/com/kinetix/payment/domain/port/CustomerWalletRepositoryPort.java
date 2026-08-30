package com.kinetix.payment.domain.port;

import com.kinetix.payment.domain.entity.CustomerWallet;
import java.util.Optional;

public interface CustomerWalletRepositoryPort {
    Optional<CustomerWallet> findByCustomerId(Long customerId);
    CustomerWallet save(CustomerWallet wallet);
}
