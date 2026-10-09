package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.DriverWallet;
import com.kinetix.payment.domain.entity.EscrowHold;
import com.kinetix.payment.domain.entity.EscrowIdempotencyRecord;
import com.kinetix.payment.domain.entity.EscrowOperation;
import com.kinetix.payment.domain.entity.MerchantWallet;
import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.entity.SuspenseWallet;
import com.kinetix.payment.domain.exception.DomainException;
import com.kinetix.payment.domain.exception.IdempotencyConflictException;
import com.kinetix.payment.domain.port.AdvisoryLockPort;
import com.kinetix.payment.domain.port.CustomerWalletRepositoryPort;
import com.kinetix.payment.domain.port.DriverWalletRepositoryPort;
import com.kinetix.payment.domain.port.EscrowIdempotencyRepositoryPort;
import com.kinetix.payment.domain.port.EscrowRepositoryPort;
import com.kinetix.payment.domain.port.MerchantWalletRepositoryPort;
import com.kinetix.payment.domain.port.PaymentTransactionRepositoryPort;
import com.kinetix.payment.domain.port.SuspenseWalletRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EscrowGoodsRefundTest {
    private static final String ORDER = "ORD-RETURN-1";
    private static final String CUSTOMER = "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7";
    private static final String MERCHANT = "3c9a77b1-58de-4a01-8f2e-6d4b19c0a8f3";
    private static final String DRIVER = "b7e2c05f-9a34-4c88-b1d6-0e7a3f52d914";
    private static final BigDecimal TOTAL = new BigDecimal("150000.00");
    private static final BigDecimal MERCHANT_AMOUNT = new BigDecimal("130000.00");
    private static final BigDecimal SHIPPING = new BigDecimal("20000.00");

    private EscrowRepositoryPort escrowRepository;
    private CustomerWalletRepositoryPort customerWalletRepository;
    private MerchantWalletRepositoryPort merchantWalletRepository;
    private DriverWalletRepositoryPort driverWalletRepository;
    private SuspenseWalletRepositoryPort suspenseWalletRepository;
    private EscrowIdempotencyRepositoryPort idempotencyRepository;
    private PaymentTransactionRepositoryPort paymentTransactionRepository;
    private EscrowService escrowService;

    @BeforeEach
    void setUp() {
        escrowRepository = mock(EscrowRepositoryPort.class);
        customerWalletRepository = mock(CustomerWalletRepositoryPort.class);
        merchantWalletRepository = mock(MerchantWalletRepositoryPort.class);
        driverWalletRepository = mock(DriverWalletRepositoryPort.class);
        suspenseWalletRepository = mock(SuspenseWalletRepositoryPort.class);
        idempotencyRepository = mock(EscrowIdempotencyRepositoryPort.class);
        paymentTransactionRepository = mock(PaymentTransactionRepositoryPort.class);

        when(escrowRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(idempotencyRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(customerWalletRepository.findByCustomerPrincipalIdForUpdate(CUSTOMER))
            .thenReturn(Optional.of(CustomerWallet.createInitial(CUSTOMER)));
        when(merchantWalletRepository.findByMerchantPrincipalIdForUpdate(MERCHANT))
            .thenReturn(Optional.of(MerchantWallet.createInitial(MERCHANT).addPendingEscrow(MERCHANT_AMOUNT)));

        escrowService = new EscrowService(
            escrowRepository, customerWalletRepository, merchantWalletRepository, driverWalletRepository,
            suspenseWalletRepository, paymentTransactionRepository, idempotencyRepository,
            new DirectTransactionRunner(), mock(AdvisoryLockPort.class)
        );
    }

    private EscrowHold hold(EscrowHold.EscrowStatus status, BigDecimal goodsRefunded, String driver, Instant settledAt) {
        EscrowHold hold = new EscrowHold(
            42L, ORDER, CUSTOMER, MERCHANT, driver, TOTAL, MERCHANT_AMOUNT, SHIPPING,
            status, Instant.now(), null, settledAt, goodsRefunded
        );
        when(escrowRepository.findByOrderNumberForUpdate(ORDER)).thenReturn(Optional.of(hold));
        when(escrowRepository.findByOrderNumber(ORDER)).thenReturn(Optional.of(hold));
        return hold;
    }

    @Test
    void returnedGoodsMoveFromTheMerchantsPendingShareToTheBuyer() {
        hold(EscrowHold.EscrowStatus.HELD, BigDecimal.ZERO, DRIVER, Instant.now());

        EscrowOutcome outcome = escrowService.refundGoods(ORDER, new BigDecimal("40000.00"), "two of three came back", "return:RMA-1");

        assertEquals(0, new BigDecimal("40000.00").compareTo(outcome.hold().goodsRefundedAmount()));
        ArgumentCaptor<MerchantWallet> merchant = ArgumentCaptor.forClass(MerchantWallet.class);
        ArgumentCaptor<CustomerWallet> customer = ArgumentCaptor.forClass(CustomerWallet.class);
        verify(merchantWalletRepository).save(merchant.capture());
        verify(customerWalletRepository).save(customer.capture());
        assertEquals(0, new BigDecimal("90000.00").compareTo(merchant.getValue().pendingEscrowBalance()));
        assertEquals(0, new BigDecimal("40000.00").compareTo(customer.getValue().balance()));
        verifyNoInteractions(driverWalletRepository);
    }

    @Test
    void moreThanTheMerchantStillHoldsIsNotRefunded() {
        hold(EscrowHold.EscrowStatus.HELD, new BigDecimal("100000.00"), DRIVER, Instant.now());

        assertThrows(IllegalArgumentException.class,
            () -> escrowService.refundGoods(ORDER, new BigDecimal("30000.01"), "too much", "return:RMA-2")
        );
        verify(customerWalletRepository, never()).save(any());
    }

    @Test
    void goodsAreNotRefundedFromAHoldAlreadyPaidOut() {
        hold(EscrowHold.EscrowStatus.RELEASED, BigDecimal.ZERO, DRIVER, Instant.now());

        assertThrows(DomainException.class,
            () -> escrowService.refundGoods(ORDER, new BigDecimal("1.00"), "late", "return:RMA-3")
        );
        verify(customerWalletRepository, never()).save(any());
    }

    @Test
    void theSameReturnUnderTheSameKeyWithADifferentAmountIsRefused() {
        hold(EscrowHold.EscrowStatus.HELD, BigDecimal.ZERO, DRIVER, Instant.now());
        when(idempotencyRepository.find(eq(EscrowOperation.REFUND_GOODS), eq("return:RMA-4"))).thenReturn(Optional.of(
            EscrowIdempotencyRecord.opening(
                EscrowOperation.REFUND_GOODS, "return:RMA-4",
                com.kinetix.payment.domain.entity.IdempotencyKeySource.CALLER, ORDER,
                EscrowRequestFingerprint.forRefundGoods(ORDER, new BigDecimal("10000.00")), null
            )
        ));

        assertThrows(IdempotencyConflictException.class,
            () -> escrowService.refundGoods(ORDER, new BigDecimal("20000.00"), "again", "return:RMA-4")
        );
        verify(customerWalletRepository, never()).save(any());
    }

    @Test
    void aReleaseAfterAReturnPaysTheMerchantOnlyWhatIsStillHeld() {
        hold(EscrowHold.EscrowStatus.HELD, new BigDecimal("40000.00"), DRIVER, Instant.now());
        when(merchantWalletRepository.findByMerchantPrincipalIdForUpdate(MERCHANT))
            .thenReturn(Optional.of(MerchantWallet.createInitial(MERCHANT).addPendingEscrow(new BigDecimal("90000.00"))));

        escrowService.releaseEscrow(ORDER);

        ArgumentCaptor<MerchantWallet> merchant = ArgumentCaptor.forClass(MerchantWallet.class);
        verify(merchantWalletRepository).save(merchant.capture());
        assertEquals(0, new BigDecimal("90000.00").compareTo(merchant.getValue().availableBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(merchant.getValue().pendingEscrowBalance()));
    }

    @Test
    void aReleaseAfterEverythingCameBackPaysTheMerchantNothing() {
        hold(EscrowHold.EscrowStatus.HELD, MERCHANT_AMOUNT, DRIVER, Instant.now());

        EscrowOutcome outcome = escrowService.releaseEscrow(ORDER);

        assertEquals(EscrowHold.EscrowStatus.RELEASED, outcome.hold().status());
        verify(merchantWalletRepository, never()).save(any());
    }

    @Test
    void aFullRefundAfterAReturnGivesBackOnlyWhatWasNotAlreadyReturned() {
        hold(EscrowHold.EscrowStatus.HELD, new BigDecimal("40000.00"), null, null);
        when(merchantWalletRepository.findByMerchantPrincipalIdForUpdate(MERCHANT))
            .thenReturn(Optional.of(MerchantWallet.createInitial(MERCHANT).addPendingEscrow(new BigDecimal("90000.00"))));
        when(suspenseWalletRepository.findByPurposeForUpdate(any())).thenReturn(Optional.of(
            SuspenseWallet.createInitial(SuspenseWallet.UNASSIGNED_DRIVER_SHIPPING_FEE).addPendingEscrow(SHIPPING)
        ));
        when(suspenseWalletRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        escrowService.refundEscrow(ORDER, "cancelled before dispatch", "cancel:ORD-RETURN-1");

        ArgumentCaptor<CustomerWallet> customer = ArgumentCaptor.forClass(CustomerWallet.class);
        verify(customerWalletRepository).save(customer.capture());
        assertEquals(0, new BigDecimal("110000.00").compareTo(customer.getValue().balance()));
    }

    @Test
    void aDriverlessFeeIsPaidToTheDriverWhoDeliveredWithoutAnyPendingOfTheirOwn() {
        hold(EscrowHold.EscrowStatus.HELD, BigDecimal.ZERO, null, null);
        when(suspenseWalletRepository.findByPurposeForUpdate(any())).thenReturn(Optional.of(
            SuspenseWallet.createInitial(SuspenseWallet.UNASSIGNED_DRIVER_SHIPPING_FEE).addPendingEscrow(SHIPPING)
        ));
        when(suspenseWalletRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(driverWalletRepository.findByDriverPrincipalIdForUpdate(DRIVER))
            .thenReturn(Optional.of(DriverWallet.createInitial(DRIVER)));

        escrowService.settleShippingFee(ORDER, DRIVER);

        ArgumentCaptor<DriverWallet> driver = ArgumentCaptor.forClass(DriverWallet.class);
        verify(driverWalletRepository).save(driver.capture());
        assertEquals(0, SHIPPING.compareTo(driver.getValue().availableBalance()));
    }

    @Test
    void eachReturnIsItsOwnLineInTheLedgerAndNeverTheFullRefundsLine() {
        hold(EscrowHold.EscrowStatus.HELD, new BigDecimal("40000.00"), DRIVER, Instant.now());

        escrowService.refundGoods(ORDER, new BigDecimal("30000.00"), "one more came back", "return:RMA-2");

        ArgumentCaptor<PaymentTransaction> ledger = ArgumentCaptor.forClass(PaymentTransaction.class);
        verify(paymentTransactionRepository).save(ledger.capture());
        assertEquals("escrow:goods:" + ORDER + ":70000.00", ledger.getValue().referenceNumber());
        assertEquals(PaymentTransaction.TransactionType.REFUND, ledger.getValue().type());
        assertEquals(CUSTOMER, ledger.getValue().principalId());
        assertEquals(0, new BigDecimal("30000.00").compareTo(ledger.getValue().amount()));
    }
}
