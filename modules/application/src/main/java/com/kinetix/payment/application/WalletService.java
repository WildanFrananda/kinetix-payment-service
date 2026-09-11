package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.DriverWallet;
import com.kinetix.payment.domain.entity.MerchantWallet;
import com.kinetix.payment.domain.port.AdvisoryLockPort;
import com.kinetix.payment.domain.port.CustomerWalletRepositoryPort;
import com.kinetix.payment.domain.port.DriverWalletRepositoryPort;
import com.kinetix.payment.domain.port.MerchantWalletRepositoryPort;
import com.kinetix.payment.domain.port.TransactionRunnerPort;
import java.math.BigDecimal;

public class WalletService {
    private final CustomerWalletRepositoryPort customerWalletRepository;
    private final MerchantWalletRepositoryPort merchantWalletRepository;
    private final DriverWalletRepositoryPort driverWalletRepository;
    private final TransactionRunnerPort transactionRunner;
    private final AdvisoryLockPort advisoryLock;

    public WalletService(
        CustomerWalletRepositoryPort customerWalletRepository,
        MerchantWalletRepositoryPort merchantWalletRepository,
        DriverWalletRepositoryPort driverWalletRepository,
        TransactionRunnerPort transactionRunner,
        AdvisoryLockPort advisoryLock
    ) {
        this.customerWalletRepository = customerWalletRepository;
        this.merchantWalletRepository = merchantWalletRepository;
        this.driverWalletRepository = driverWalletRepository;
        this.transactionRunner = transactionRunner;
        this.advisoryLock = advisoryLock;
    }

    public CustomerWallet getCustomerWallet(String customerPrincipalId) {
        return customerWalletRepository.findByCustomerPrincipalId(customerPrincipalId)
            .orElseGet(() -> transactionRunner.inNewTransaction(() -> {
                advisoryLock.lockWalletOwner(customerPrincipalId);
                return customerWalletRepository.findByCustomerPrincipalIdForUpdate(customerPrincipalId)
                    .orElseGet(() -> customerWalletRepository.save(
                        CustomerWallet.createInitial(customerPrincipalId)
                    ));
            }));
    }

    public CustomerWallet topUpCustomerWallet(String customerPrincipalId, BigDecimal amount) {
        return transactionRunner.inNewTransaction(() -> {
            advisoryLock.lockWalletOwner(customerPrincipalId);
            CustomerWallet wallet = customerWalletRepository
                .findByCustomerPrincipalIdForUpdate(customerPrincipalId)
                .orElseGet(() -> CustomerWallet.createInitial(customerPrincipalId));
            return customerWalletRepository.save(wallet.topUp(amount));
        });
    }

    public MerchantWallet getMerchantWallet(String merchantPrincipalId) {
        return merchantWalletRepository.findByMerchantPrincipalId(merchantPrincipalId)
            .orElseGet(() -> transactionRunner.inNewTransaction(() -> {
                advisoryLock.lockWalletOwner(merchantPrincipalId);
                return merchantWalletRepository.findByMerchantPrincipalIdForUpdate(merchantPrincipalId)
                    .orElseGet(() -> merchantWalletRepository.save(
                        MerchantWallet.createInitial(merchantPrincipalId)
                    ));
            }));
    }

    public DriverWallet getDriverWallet(String driverPrincipalId) {
        return driverWalletRepository.findByDriverPrincipalId(driverPrincipalId)
            .orElseGet(() -> transactionRunner.inNewTransaction(() -> {
                advisoryLock.lockWalletOwner(driverPrincipalId);
                return driverWalletRepository.findByDriverPrincipalIdForUpdate(driverPrincipalId)
                    .orElseGet(() -> driverWalletRepository.save(
                        DriverWallet.createInitial(driverPrincipalId)
                    ));
            }));
    }
}
