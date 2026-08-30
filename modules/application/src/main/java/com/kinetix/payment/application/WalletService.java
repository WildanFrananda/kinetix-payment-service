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

    public CustomerWallet getCustomerWallet(Long customerId) {
        return customerWalletRepository.findByCustomerId(customerId)
            .orElseGet(() -> customerWalletRepository.save(CustomerWallet.createInitial(customerId)));
    }

    public CustomerWallet topUpCustomerWallet(Long customerId, BigDecimal amount) {
        CustomerWallet wallet = getCustomerWallet(customerId);
        CustomerWallet updated = wallet.topUp(amount);
        return customerWalletRepository.save(updated);
    }

    public MerchantWallet getMerchantWallet(Long merchantId) {
        return merchantWalletRepository.findByMerchantId(merchantId)
            .orElseGet(() -> merchantWalletRepository.save(MerchantWallet.createInitial(merchantId)));
    }

    public DriverWallet getDriverWallet(Long driverId) {
        return driverWalletRepository.findByDriverId(driverId)
            .orElseGet(() -> driverWalletRepository.save(DriverWallet.createInitial(driverId)));
    }
}
