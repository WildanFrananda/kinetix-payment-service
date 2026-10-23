package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.port.CustomerWalletRepositoryPort;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class RendezvousCustomerWallets implements CustomerWalletRepositoryPort {
    private final CustomerWalletRepositoryPort wallets;

    private final CountDownLatch readers;

    private final Duration patience;

    RendezvousCustomerWallets(CustomerWalletRepositoryPort wallets, int readers, Duration patience) {
        this.wallets = wallets;
        this.readers = new CountDownLatch(readers);
        this.patience = patience;
    }

    @Override
    public Optional<CustomerWallet> findByCustomerPrincipalId(String customerPrincipalId) {
        readers.countDown();
        try {
            readers.await(patience.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("a balance read was interrupted while waiting for the others", interrupted);
        }

        return wallets.findByCustomerPrincipalId(customerPrincipalId);
    }

    @Override
    public Optional<CustomerWallet> findByCustomerPrincipalIdForUpdate(String customerPrincipalId) {
        return wallets.findByCustomerPrincipalIdForUpdate(customerPrincipalId);
    }

    @Override
    public CustomerWallet save(CustomerWallet wallet) {
        return wallets.save(wallet);
    }
}
