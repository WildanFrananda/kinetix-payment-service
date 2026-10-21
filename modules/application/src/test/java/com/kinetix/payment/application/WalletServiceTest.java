package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.port.CustomerWalletRepositoryPort;
import com.kinetix.payment.domain.port.DriverWalletRepositoryPort;
import com.kinetix.payment.domain.port.MerchantWalletRepositoryPort;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WalletServiceTest {
    private static final String CUSTOMER = "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7";
    private static final String MERCHANT = "3c9a77b1-58de-4a01-8f2e-6d4b19c0a8f3";
    private static final String DRIVER = "b7e2c05f-9a34-4c88-b1d6-0e7a3f52d914";

    private CustomerWalletRepositoryPort customerWalletRepository;
    private MerchantWalletRepositoryPort merchantWalletRepository;
    private DriverWalletRepositoryPort driverWalletRepository;
    private WalletService walletService;

    @BeforeEach
    void setUp() {
        customerWalletRepository = mock(CustomerWalletRepositoryPort.class);
        merchantWalletRepository = mock(MerchantWalletRepositoryPort.class);
        driverWalletRepository = mock(DriverWalletRepositoryPort.class);

        walletService = new WalletService(customerWalletRepository, merchantWalletRepository, driverWalletRepository);
    }

    @Test
    void anExistingCustomerBalanceIsReadWithoutALock() {
        when(customerWalletRepository.findByCustomerPrincipalId(CUSTOMER))
            .thenReturn(Optional.of(CustomerWallet.createInitial(CUSTOMER).topUp(new BigDecimal("200000.00"))));

        Optional<CustomerWallet> wallet = walletService.findCustomerWallet(CUSTOMER);

        assertEquals(0, new BigDecimal("200000.00").compareTo(wallet.orElseThrow().balance()));
        verify(customerWalletRepository, never()).findByCustomerPrincipalIdForUpdate(any());
    }

    @Test
    void readingABalanceNobodyHasOpenedCreatesNoCustomerWallet() {
        when(customerWalletRepository.findByCustomerPrincipalId(CUSTOMER)).thenReturn(Optional.empty());

        assertTrue(walletService.findCustomerWallet(CUSTOMER).isEmpty());

        verify(customerWalletRepository, never()).save(any());
    }

    @Test
    void readingABalanceNobodyHasOpenedCreatesNoMerchantWallet() {
        when(merchantWalletRepository.findByMerchantPrincipalId(MERCHANT)).thenReturn(Optional.empty());

        assertTrue(walletService.findMerchantWallet(MERCHANT).isEmpty());

        verify(merchantWalletRepository, never()).save(any());
    }

    @Test
    void readingABalanceNobodyHasOpenedCreatesNoDriverWallet() {
        when(driverWalletRepository.findByDriverPrincipalId(DRIVER)).thenReturn(Optional.empty());

        assertTrue(walletService.findDriverWallet(DRIVER).isEmpty());

        verify(driverWalletRepository, never()).save(any());
    }
}
