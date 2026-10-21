package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.DriverWallet;
import com.kinetix.payment.domain.entity.MerchantWallet;
import com.kinetix.payment.domain.port.CustomerWalletRepositoryPort;
import com.kinetix.payment.domain.port.DriverWalletRepositoryPort;
import com.kinetix.payment.domain.port.MerchantWalletRepositoryPort;
import java.util.Optional;

public class WalletService {
    private final CustomerWalletRepositoryPort customerWalletRepository;
    private final MerchantWalletRepositoryPort merchantWalletRepository;
    private final DriverWalletRepositoryPort driverWalletRepository;

    public WalletService(
        CustomerWalletRepositoryPort customerWalletRepository,
        MerchantWalletRepositoryPort merchantWalletRepository,
        DriverWalletRepositoryPort driverWalletRepository
    ) {
        this.customerWalletRepository = customerWalletRepository;
        this.merchantWalletRepository = merchantWalletRepository;
        this.driverWalletRepository = driverWalletRepository;
    }

    public Optional<CustomerWallet> findCustomerWallet(String customerPrincipalId) {
        return customerWalletRepository.findByCustomerPrincipalId(customerPrincipalId);
    }

    public Optional<MerchantWallet> findMerchantWallet(String merchantPrincipalId) {
        return merchantWalletRepository.findByMerchantPrincipalId(merchantPrincipalId);
    }

    public Optional<DriverWallet> findDriverWallet(String driverPrincipalId) {
        return driverWalletRepository.findByDriverPrincipalId(driverPrincipalId);
    }
}
