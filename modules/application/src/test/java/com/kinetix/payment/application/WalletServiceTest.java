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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
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
    private AdvisoryLockPort advisoryLock;
    private CountingTransactionRunner transactionRunner;
    private AtomicBoolean lockedInsideATransaction;
    private WalletService walletService;

    @BeforeEach
    void setUp() {
        customerWalletRepository = mock(CustomerWalletRepositoryPort.class);
        merchantWalletRepository = mock(MerchantWalletRepositoryPort.class);
        driverWalletRepository = mock(DriverWalletRepositoryPort.class);
        advisoryLock = mock(AdvisoryLockPort.class);
        transactionRunner = new CountingTransactionRunner();

        lockedInsideATransaction = new AtomicBoolean(false);
        doAnswer(call -> {
            lockedInsideATransaction.set(transactionRunner.insideTransaction());
            return null;
        }).when(advisoryLock).lockWalletOwner(any());

        when(customerWalletRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(merchantWalletRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(driverWalletRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        walletService = new WalletService(
            customerWalletRepository,
            merchantWalletRepository,
            driverWalletRepository,
            transactionRunner,
            advisoryLock
        );
    }

    @Test
    void topUpCustomerWallet_readsTheBalanceThroughTheSameLockTheEscrowDebitTakes() {
        when(customerWalletRepository.findByCustomerPrincipalIdForUpdate(CUSTOMER))
            .thenReturn(Optional.of(CustomerWallet.createInitial(CUSTOMER).topUp(new BigDecimal("200000.00")))
        );

        CustomerWallet toppedUp = walletService.topUpCustomerWallet(CUSTOMER, new BigDecimal("50000.00"));

        assertEquals(0, new BigDecimal("250000.00").compareTo(toppedUp.balance()));
        verify(customerWalletRepository).findByCustomerPrincipalIdForUpdate(CUSTOMER);
        verify(customerWalletRepository, never()).findByCustomerPrincipalId(any());
    }

    @Test
    void topUpCustomerWallet_locksAndWritesInsideOneTransaction() {
        when(customerWalletRepository.findByCustomerPrincipalIdForUpdate(CUSTOMER))
            .thenReturn(Optional.of(CustomerWallet.createInitial(CUSTOMER)));

        walletService.topUpCustomerWallet(CUSTOMER, new BigDecimal("50000.00"));

        assertEquals(1, transactionRunner.transactions());
        assertTrue(lockedInsideATransaction.get());
        InOrder order = inOrder(advisoryLock, customerWalletRepository);
        order.verify(advisoryLock).lockWalletOwner(CUSTOMER);
        order.verify(customerWalletRepository).findByCustomerPrincipalIdForUpdate(CUSTOMER);
        order.verify(customerWalletRepository).save(any());
    }

    @Test
    void topUpCustomerWallet_createsTheFirstWalletUnderTheOwnerLock() {
        when(customerWalletRepository.findByCustomerPrincipalIdForUpdate(CUSTOMER))
            .thenReturn(Optional.empty()
        );

        CustomerWallet created = walletService.topUpCustomerWallet(CUSTOMER, new BigDecimal("50000.00"));

        assertEquals(0, new BigDecimal("50000.00").compareTo(created.balance()));
        verify(advisoryLock).lockWalletOwner(CUSTOMER);
    }

    @Test
    void getCustomerWallet_readsAnExistingBalanceWithNoLockAndNoTransaction() {
        when(customerWalletRepository.findByCustomerPrincipalId(CUSTOMER))
            .thenReturn(Optional.of(CustomerWallet.createInitial(CUSTOMER).topUp(new BigDecimal("200000.00"))));

        CustomerWallet wallet = walletService.getCustomerWallet(CUSTOMER);

        assertEquals(0, new BigDecimal("200000.00").compareTo(wallet.balance()));
        assertEquals(0, transactionRunner.transactions());
        verify(advisoryLock, never()).lockWalletOwner(any());
        verify(customerWalletRepository, never()).findByCustomerPrincipalIdForUpdate(any());
    }

    @Test
    void getCustomerWallet_createsAMissingWalletUnderTheOwnerLockAndRereadsFirst() {
        when(customerWalletRepository.findByCustomerPrincipalId(CUSTOMER)).thenReturn(Optional.empty());
        when(customerWalletRepository.findByCustomerPrincipalIdForUpdate(CUSTOMER))
            .thenReturn(Optional.empty());

        walletService.getCustomerWallet(CUSTOMER);

        assertEquals(1, transactionRunner.transactions());
        InOrder order = inOrder(advisoryLock, customerWalletRepository);
        order.verify(advisoryLock).lockWalletOwner(CUSTOMER);
        order.verify(customerWalletRepository).findByCustomerPrincipalIdForUpdate(CUSTOMER);
        order.verify(customerWalletRepository).save(any());
    }

    @Test
    void getCustomerWallet_adoptsARowThatAppearedWhileItWasWaitingForTheLock() {
        when(customerWalletRepository.findByCustomerPrincipalId(CUSTOMER)).thenReturn(Optional.empty());
        when(customerWalletRepository.findByCustomerPrincipalIdForUpdate(CUSTOMER))
            .thenReturn(Optional.of(CustomerWallet.createInitial(CUSTOMER).topUp(new BigDecimal("75000.00"))));

        CustomerWallet wallet = walletService.getCustomerWallet(CUSTOMER);

        assertEquals(0, new BigDecimal("75000.00").compareTo(wallet.balance()));
        verify(customerWalletRepository, never()).save(any());
    }

    @Test
    void getMerchantWallet_createsAMissingWalletUnderTheOwnerLock() {
        when(merchantWalletRepository.findByMerchantPrincipalId(MERCHANT)).thenReturn(Optional.empty());
        when(merchantWalletRepository.findByMerchantPrincipalIdForUpdate(MERCHANT))
            .thenReturn(Optional.empty());

        MerchantWallet wallet = walletService.getMerchantWallet(MERCHANT);

        assertEquals(MERCHANT, wallet.merchantPrincipalId());
        assertEquals(1, transactionRunner.transactions());
        verify(advisoryLock).lockWalletOwner(MERCHANT);
    }

    @Test
    void getDriverWallet_createsAMissingWalletUnderTheOwnerLock() {
        when(driverWalletRepository.findByDriverPrincipalId(DRIVER)).thenReturn(Optional.empty());
        when(driverWalletRepository.findByDriverPrincipalIdForUpdate(DRIVER)).thenReturn(Optional.empty());

        DriverWallet wallet = walletService.getDriverWallet(DRIVER);

        assertEquals(DRIVER, wallet.driverPrincipalId());
        assertEquals(1, transactionRunner.transactions());
        verify(advisoryLock).lockWalletOwner(DRIVER);
    }
}
