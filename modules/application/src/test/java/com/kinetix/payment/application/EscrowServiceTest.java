package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.DriverWallet;
import com.kinetix.payment.domain.entity.EscrowHold;
import com.kinetix.payment.domain.entity.MerchantWallet;
import com.kinetix.payment.domain.exception.InsufficientBalanceException;
import com.kinetix.payment.domain.port.CustomerWalletRepositoryPort;
import com.kinetix.payment.domain.port.DriverWalletRepositoryPort;
import com.kinetix.payment.domain.port.EscrowRepositoryPort;
import com.kinetix.payment.domain.port.MerchantWalletRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EscrowServiceTest {
    private static final String MERCHANT = "3c9a77b1-58de-4a01-8f2e-6d4b19c0a8f3";
    private static final String DRIVER = "b7e2c05f-9a34-4c88-b1d6-0e7a3f52d914";
    private EscrowRepositoryPort escrowRepository;
    private CustomerWalletRepositoryPort customerWalletRepository;
    private MerchantWalletRepositoryPort merchantWalletRepository;
    private DriverWalletRepositoryPort driverWalletRepository;
    private EscrowService escrowService;

    @BeforeEach
    void setUp() {
        escrowRepository = mock(EscrowRepositoryPort.class);
        customerWalletRepository = mock(CustomerWalletRepositoryPort.class);
        merchantWalletRepository = mock(MerchantWalletRepositoryPort.class);
        driverWalletRepository = mock(DriverWalletRepositoryPort.class);

        escrowService = new EscrowService(
            escrowRepository,
            customerWalletRepository,
            merchantWalletRepository,
            driverWalletRepository
        );
    }

    @Test
    void createEscrowHold_rejectsInsufficientCustomerBalance() {
        String customerPrincipalId = "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7";
        CustomerWallet emptyWallet = CustomerWallet.createInitial(customerPrincipalId);
        when(customerWalletRepository.findByCustomerPrincipalId(customerPrincipalId)).thenReturn(Optional.of(emptyWallet));

        assertThrows(InsufficientBalanceException.class, () ->
            escrowService.createEscrowHold("ORD-1001", customerPrincipalId, MERCHANT, DRIVER, new BigDecimal("150000.00"), new BigDecimal("130000.00"), new BigDecimal("20000.00"))
        );
    }

    @Test
    void createEscrowHold_successWhenBalanceSufficient() {
        String customerPrincipalId = "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7";
        CustomerWallet fundedWallet = CustomerWallet.createInitial(customerPrincipalId).topUp(new BigDecimal("200000.00"));
        when(customerWalletRepository.findByCustomerPrincipalId(customerPrincipalId)).thenReturn(Optional.of(fundedWallet));
        when(escrowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EscrowHold hold = escrowService.createEscrowHold(
            "ORD-1001",
            customerPrincipalId,
            MERCHANT,
            DRIVER,
            new BigDecimal("150000.00"),
            new BigDecimal("130000.00"),
            new BigDecimal("20000.00")
        );

        assertNotNull(hold);
        assertEquals("ORD-1001", hold.orderNumber());
        assertEquals(EscrowHold.EscrowStatus.HELD, hold.status());
        verify(customerWalletRepository).save(any());
        verify(merchantWalletRepository).save(any());
        verify(driverWalletRepository).save(any());
    }
}
