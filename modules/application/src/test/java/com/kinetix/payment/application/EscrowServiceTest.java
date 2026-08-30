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
        Long customerId = 101L;
        CustomerWallet emptyWallet = CustomerWallet.createInitial(customerId);
        when(customerWalletRepository.findByCustomerId(customerId)).thenReturn(Optional.of(emptyWallet));

        assertThrows(InsufficientBalanceException.class, () ->
            escrowService.createEscrowHold("ORD-1001", customerId, 50L, 10L, new BigDecimal("150000.00"), new BigDecimal("130000.00"), new BigDecimal("20000.00"))
        );
    }

    @Test
    void createEscrowHold_successWhenBalanceSufficient() {
        Long customerId = 101L;
        CustomerWallet fundedWallet = CustomerWallet.createInitial(customerId).topUp(new BigDecimal("200000.00"));
        when(customerWalletRepository.findByCustomerId(customerId)).thenReturn(Optional.of(fundedWallet));
        when(escrowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EscrowHold hold = escrowService.createEscrowHold(
            "ORD-1001",
            customerId,
            50L,
            10L,
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
