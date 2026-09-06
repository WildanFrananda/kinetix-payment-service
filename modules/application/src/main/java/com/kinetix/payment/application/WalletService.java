package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.DriverWallet;
import com.kinetix.payment.domain.entity.MerchantWallet;
import com.kinetix.payment.domain.port.CustomerWalletRepositoryPort;
import com.kinetix.payment.domain.port.DriverWalletRepositoryPort;
import com.kinetix.payment.domain.port.MerchantWalletRepositoryPort;
import java.math.BigDecimal;

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

    public CustomerWallet getCustomerWallet(String customerPrincipalId) {
        return customerWalletRepository.findByCustomerPrincipalId(customerPrincipalId)
            .orElseGet(() -> customerWalletRepository.save(CustomerWallet.createInitial(customerPrincipalId)));
    }

    public CustomerWallet topUpCustomerWallet(String customerPrincipalId, BigDecimal amount) {
        CustomerWallet wallet = getCustomerWallet(customerPrincipalId);
        CustomerWallet updated = wallet.topUp(amount);
        return customerWalletRepository.save(updated);
    }

    public MerchantWallet getMerchantWallet(String merchantPrincipalId) {
        return merchantWalletRepository.findByMerchantPrincipalId(merchantPrincipalId)
            .orElseGet(() -> merchantWalletRepository.save(MerchantWallet.createInitial(merchantPrincipalId)));
    }

    public DriverWallet getDriverWallet(String driverPrincipalId) {
        return driverWalletRepository.findByDriverPrincipalId(driverPrincipalId)
            .orElseGet(() -> driverWalletRepository.save(DriverWallet.createInitial(driverPrincipalId)));
    }
}
