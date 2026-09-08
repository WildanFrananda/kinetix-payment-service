package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.EscrowHold;
import com.kinetix.payment.domain.entity.EscrowIdempotencyRecord;
import com.kinetix.payment.domain.entity.EscrowOperation;
import com.kinetix.payment.domain.entity.IdempotencyKeySource;
import com.kinetix.payment.domain.entity.MerchantWallet;
import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.exception.DomainException;
import com.kinetix.payment.domain.exception.DuplicateIdempotencyKeyException;
import com.kinetix.payment.domain.exception.DuplicateOrderNumberException;
import com.kinetix.payment.domain.exception.IdempotencyConflictException;
import com.kinetix.payment.domain.exception.InsufficientBalanceException;
import com.kinetix.payment.domain.exception.OrderAlreadyUnwoundException;
import com.kinetix.payment.domain.port.AdvisoryLockPort;
import com.kinetix.payment.domain.port.CustomerWalletRepositoryPort;
import com.kinetix.payment.domain.port.DriverWalletRepositoryPort;
import com.kinetix.payment.domain.port.EscrowIdempotencyRepositoryPort;
import com.kinetix.payment.domain.port.EscrowRepositoryPort;
import com.kinetix.payment.domain.port.MerchantWalletRepositoryPort;
import com.kinetix.payment.domain.port.PaymentTransactionRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EscrowServiceTest {
    private static final String ORDER = "ORD-1001";
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
    private PaymentTransactionRepositoryPort paymentTransactionRepository;
    private EscrowIdempotencyRepositoryPort idempotencyRepository;
    private AdvisoryLockPort advisoryLock;
    private EscrowService escrowService;

    @BeforeEach
    void setUp() {
        escrowRepository = mock(EscrowRepositoryPort.class);
        customerWalletRepository = mock(CustomerWalletRepositoryPort.class);
        merchantWalletRepository = mock(MerchantWalletRepositoryPort.class);
        driverWalletRepository = mock(DriverWalletRepositoryPort.class);
        paymentTransactionRepository = mock(PaymentTransactionRepositoryPort.class);
        idempotencyRepository = mock(EscrowIdempotencyRepositoryPort.class);
        advisoryLock = mock(AdvisoryLockPort.class);

        when(escrowRepository.save(any())).thenAnswer(call -> withId(call.getArgument(0), 42L));
        when(idempotencyRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        escrowService = new EscrowService(
            escrowRepository,
            customerWalletRepository,
            merchantWalletRepository,
            driverWalletRepository,
            paymentTransactionRepository,
            idempotencyRepository,
            new DirectTransactionRunner(),
            advisoryLock
        );
    }

    @Test
    void createEscrowHold_rejectsInsufficientCustomerBalance() {
        givenCustomerWallet(BigDecimal.ZERO);

        assertThrows(InsufficientBalanceException.class, () -> escrowService.createEscrowHold(command(TOTAL)));
    }

    @Test
    void createEscrowHold_successWhenBalanceSufficient() {
        givenCustomerWallet(new BigDecimal("200000.00"));

        EscrowOutcome outcome = escrowService.createEscrowHold(command(TOTAL));

        assertNotNull(outcome.hold());
        assertEquals(ORDER, outcome.hold().orderNumber());
        assertEquals(EscrowHold.EscrowStatus.HELD, outcome.hold().status());
        assertFalse(outcome.alreadyApplied());
        verify(customerWalletRepository).save(any());
        verify(merchantWalletRepository).save(any());
        verify(driverWalletRepository).save(any());
    }

    @Test
    void createEscrowHold_locksEveryWalletItChanges() {
        givenCustomerWallet(new BigDecimal("200000.00"));

        escrowService.createEscrowHold(command(TOTAL));

        verify(customerWalletRepository).findByCustomerPrincipalIdForUpdate(CUSTOMER);
        verify(merchantWalletRepository).findByMerchantPrincipalIdForUpdate(MERCHANT);
        verify(driverWalletRepository).findByDriverPrincipalIdForUpdate(DRIVER);
        verify(customerWalletRepository, never()).findByCustomerPrincipalId(any());
    }

    @Test
    void createEscrowHold_takesTheOrderLockBeforeItWritesAnything() {
        givenCustomerWallet(new BigDecimal("200000.00"));

        escrowService.createEscrowHold(command(TOTAL));

        InOrder beforeTheDedupeRow = inOrder(advisoryLock, idempotencyRepository);
        beforeTheDedupeRow.verify(advisoryLock).lockOrder(ORDER);
        beforeTheDedupeRow.verify(idempotencyRepository, atLeastOnce()).save(any());

        InOrder beforeTheDebit = inOrder(advisoryLock, customerWalletRepository);
        beforeTheDebit.verify(advisoryLock).lockOrder(ORDER);
        beforeTheDebit.verify(customerWalletRepository).findByCustomerPrincipalIdForUpdate(CUSTOMER);
    }

    @Test
    void releaseEscrow_takesTheOrderLockBeforeItWritesAnything() {
        givenExistingHold(EscrowHold.EscrowStatus.HELD);
        when(merchantWalletRepository.findByMerchantPrincipalIdForUpdate(MERCHANT))
            .thenReturn(Optional.of(MerchantWallet.createInitial(MERCHANT).addPendingEscrow(MERCHANT_AMOUNT))
        );

        escrowService.releaseEscrow(ORDER);

        InOrder order = inOrder(advisoryLock, idempotencyRepository);
        order.verify(advisoryLock).lockOrder(ORDER);
        order.verify(idempotencyRepository, atLeastOnce()).save(any());
    }

    @Test
    void refundEscrow_takesTheOrderLockBeforeItWritesAnything() {
        when(escrowRepository.findByOrderNumberForUpdate(ORDER)).thenReturn(Optional.empty());

        escrowService.refundEscrow(ORDER, "checkout never finished", null);

        InOrder beforeTheTombstone = inOrder(advisoryLock, idempotencyRepository);
        beforeTheTombstone.verify(advisoryLock).lockOrder(ORDER);
        beforeTheTombstone.verify(idempotencyRepository, atLeastOnce()).save(any());

        InOrder beforeTheHoldIsRead = inOrder(advisoryLock, escrowRepository);
        beforeTheHoldIsRead.verify(advisoryLock).lockOrder(ORDER);
        beforeTheHoldIsRead.verify(escrowRepository).findByOrderNumberForUpdate(ORDER);
    }

    @Test
    void createEscrowHold_neverInsertsACustomerWalletThatDoesNotExist() {
        when(customerWalletRepository.findByCustomerPrincipalIdForUpdate(CUSTOMER))
            .thenReturn(Optional.empty()
        );

        assertThrows(InsufficientBalanceException.class,
            () -> escrowService.createEscrowHold(command(TOTAL))
        );
        verify(customerWalletRepository, never()).save(any());
    }

    @Test
    void createEscrowHold_writesTheLedgerRowThatMakesADoubleChargeAnswerable() {
        givenCustomerWallet(new BigDecimal("200000.00"));

        escrowService.createEscrowHold(command(TOTAL));

        verify(paymentTransactionRepository).save(argThatIsCheckoutLedgerRow());
    }

    @Test
    void createEscrowHold_repeatUnderTheSameKeyTouchesNoWallet() {
        givenExistingHold(EscrowHold.EscrowStatus.HELD);
        givenRecordedCreate(fingerprintOf(TOTAL));

        EscrowOutcome outcome = escrowService.createEscrowHold(command(TOTAL));

        assertTrue(outcome.alreadyApplied());
        assertEquals(ORDER, outcome.hold().orderNumber());
        verify(customerWalletRepository, never()).save(any());
        verify(merchantWalletRepository, never()).save(any());
        verify(paymentTransactionRepository, never()).save(any());
    }

    @Test
    void createEscrowHold_repeatUnderTheSameKeyWithDifferentTermsIsRefused() {
        givenExistingHold(EscrowHold.EscrowStatus.HELD);
        givenRecordedCreate(fingerprintOf(TOTAL));

        assertThrows(IdempotencyConflictException.class,
            () -> escrowService.createEscrowHold(command(new BigDecimal("500000.00")))
        );
        verify(customerWalletRepository, never()).save(any());
    }

    @Test
    void createEscrowHold_repeatAtADifferentScaleIsTheSameRequest() {
        givenExistingHold(EscrowHold.EscrowStatus.HELD);
        givenRecordedCreate(fingerprintOf(new BigDecimal("150000")));

        EscrowOutcome outcome = escrowService.createEscrowHold(command(new BigDecimal("150000.00")));

        assertTrue(outcome.alreadyApplied());
    }

    @Test
    void createEscrowHold_repeatAgainstARefundedHoldIsRefusedRatherThanReplayed() {
        givenExistingHold(EscrowHold.EscrowStatus.REFUNDED);
        givenRecordedCreate(fingerprintOf(TOTAL));

        DomainException refusal = assertThrows(DomainException.class,
            () -> escrowService.createEscrowHold(command(TOTAL))
        );
        assertTrue(refusal.getMessage().contains("REFUNDED"));
        verify(customerWalletRepository, never()).save(any());
    }

    @Test
    void createEscrowHold_isRefusedOnceTheOrderHasBeenRefunded() {
        givenCustomerWallet(new BigDecimal("200000.00"));
        when(idempotencyRepository.existsFor(EscrowOperation.REFUND, ORDER)).thenReturn(true);

        assertThrows(OrderAlreadyUnwoundException.class,
            () -> escrowService.createEscrowHold(command(TOTAL))
        );
        verify(customerWalletRepository, never()).save(any());
    }

    @Test
    void createEscrowHold_losingTheKeyRaceReplaysTheWinnersAnswer() {
        givenCustomerWallet(new BigDecimal("200000.00"));
        givenExistingHold(EscrowHold.EscrowStatus.HELD);
        doThrow(new DuplicateIdempotencyKeyException("already recorded"))
            .when(idempotencyRepository).save(any());
        when(idempotencyRepository.find(EscrowOperation.CREATE_HOLD, "order:" + ORDER))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(recordedCreate(fingerprintOf(TOTAL)))
        );

        EscrowOutcome outcome = escrowService.createEscrowHold(command(TOTAL));

        assertTrue(outcome.alreadyApplied());
    }

    @Test
    void createEscrowHold_aSecondKeyForTheSameOrderReplaysWhenTheTermsMatch() {
        givenCustomerWallet(new BigDecimal("200000.00"));
        givenExistingHold(EscrowHold.EscrowStatus.HELD);
        doThrow(new DuplicateOrderNumberException("an escrow hold already exists for this order"))
            .when(escrowRepository).save(any());

        EscrowOutcome outcome = escrowService.createEscrowHold(command(TOTAL, "hold:" + ORDER));

        assertTrue(outcome.alreadyApplied());
        assertEquals(ORDER, outcome.hold().orderNumber());
    }

    @Test
    void createEscrowHold_aSecondKeyForARefundedOrderIsRefused() {
        givenCustomerWallet(new BigDecimal("200000.00"));
        givenExistingHold(EscrowHold.EscrowStatus.REFUNDED);
        doThrow(new DuplicateOrderNumberException("an escrow hold already exists for this order"))
            .when(escrowRepository).save(any());

        assertThrows(DomainException.class,
            () -> escrowService.createEscrowHold(command(TOTAL, "hold:" + ORDER))
        );
    }

    @Test
    void releaseEscrow_refusesARefundedHoldInsteadOfPayingTheMerchantTwice() {
        givenExistingHold(EscrowHold.EscrowStatus.REFUNDED);

        assertThrows(DomainException.class, () -> escrowService.releaseEscrow(ORDER));
        verify(merchantWalletRepository, never()).save(any());
        verify(driverWalletRepository, never()).save(any());
    }

    @Test
    void releaseEscrow_repeatOnAReleasedHoldChangesNothing() {
        givenExistingHold(EscrowHold.EscrowStatus.RELEASED);

        EscrowOutcome outcome = escrowService.releaseEscrow(ORDER);

        assertTrue(outcome.alreadyApplied());
        verify(merchantWalletRepository, never()).save(any());
    }

    @Test
    void releaseEscrow_paysTheMerchantUnderTheHoldRowLock() {
        givenExistingHold(EscrowHold.EscrowStatus.HELD);
        when(merchantWalletRepository.findByMerchantPrincipalIdForUpdate(MERCHANT))
            .thenReturn(Optional.of(MerchantWallet.createInitial(MERCHANT).addPendingEscrow(MERCHANT_AMOUNT)));

        EscrowOutcome outcome = escrowService.releaseEscrow(ORDER);

        assertFalse(outcome.alreadyApplied());
        assertEquals(EscrowHold.EscrowStatus.RELEASED, outcome.hold().status());
        verify(escrowRepository).findByOrderNumberForUpdate(ORDER);
        verify(escrowRepository, never()).findByOrderNumber(ORDER);
    }

    @Test
    void refundEscrow_refusesAReleasedHoldWithAClassifiableFailure() {
        givenExistingHold(EscrowHold.EscrowStatus.RELEASED);

        assertThrows(DomainException.class, () -> escrowService.refundEscrow(ORDER, "cancelled", null));
        verify(customerWalletRepository, never()).save(any());
    }

    @Test
    void refundEscrow_repeatOnARefundedHoldCreditsNobodyASecondTime() {
        givenExistingHold(EscrowHold.EscrowStatus.REFUNDED);

        EscrowOutcome outcome = escrowService.refundEscrow(ORDER, "cancelled", null);

        assertTrue(outcome.alreadyApplied());
        verify(customerWalletRepository, never()).save(any());
    }

    @Test
    void refundEscrow_repeatOnATombstoneWithStillNoHoldAnswersAlreadyAppliedNotAFailure() {
        when(escrowRepository.findByOrderNumberForUpdate(ORDER)).thenReturn(Optional.empty());
        when(idempotencyRepository.find(EscrowOperation.REFUND, "order:" + ORDER))
            .thenReturn(Optional.of(new EscrowIdempotencyRecord(
                7L, EscrowOperation.REFUND, "order:" + ORDER, IdempotencyKeySource.DERIVED, ORDER,
                EscrowRequestFingerprint.forRefund(ORDER), null, "checkout never finished",
                Instant.now()
            )));

        EscrowOutcome outcome = escrowService.refundEscrow(ORDER, "checkout never finished", null);

        assertNull(outcome.hold());
        assertTrue(outcome.alreadyApplied());
        verify(customerWalletRepository, never()).save(any());
        verify(idempotencyRepository, never()).save(any());
    }

    @Test
    void refundEscrow_withNoHoldRecordsATombstone() {
        when(escrowRepository.findByOrderNumberForUpdate(ORDER)).thenReturn(Optional.empty());

        EscrowOutcome outcome = escrowService.refundEscrow(ORDER, "checkout never finished", null);

        assertNull(outcome.hold());
        assertFalse(outcome.alreadyApplied());
        verify(idempotencyRepository).save(argThatIsRefundTombstone());
    }

    @Test
    void refundEscrow_retryingATombstoneUnwindsAHoldThatCommittedAfterwards() {
        givenExistingHold(EscrowHold.EscrowStatus.HELD);
        when(customerWalletRepository.findByCustomerPrincipalIdForUpdate(CUSTOMER))
            .thenReturn(Optional.of(CustomerWallet.createInitial(CUSTOMER))
        );
        when(idempotencyRepository.find(EscrowOperation.REFUND, "order:" + ORDER))
            .thenReturn(Optional.of(new EscrowIdempotencyRecord(
                7L, EscrowOperation.REFUND, "order:" + ORDER, IdempotencyKeySource.DERIVED, ORDER,
                EscrowRequestFingerprint.forRefund(ORDER), null, "checkout never finished",
                Instant.now()
            )));

        EscrowOutcome outcome = escrowService.refundEscrow(ORDER, "checkout never finished", null);

        assertNotNull(outcome.hold());
        assertEquals(EscrowHold.EscrowStatus.REFUNDED, outcome.hold().status());
        verify(customerWalletRepository).save(any());
    }

    private CreateEscrowHoldCommand command(BigDecimal total) {
        return command(total, null);
    }

    private CreateEscrowHoldCommand command(BigDecimal total, String idempotencyKey) {
        return new CreateEscrowHoldCommand(
            ORDER, CUSTOMER, MERCHANT, DRIVER, total, MERCHANT_AMOUNT, SHIPPING,
            idempotencyKey, fingerprintOf(total)
        );
    }

    private static String fingerprintOf(BigDecimal total) {
        return EscrowRequestFingerprint.forCreateHold(
            ORDER, CUSTOMER, MERCHANT, DRIVER, total, MERCHANT_AMOUNT, SHIPPING
        );
    }

    private void givenCustomerWallet(BigDecimal balance) {
        CustomerWallet wallet = balance.signum() == 0
            ? CustomerWallet.createInitial(CUSTOMER)
            : CustomerWallet.createInitial(CUSTOMER).topUp(balance);
        when(customerWalletRepository.findByCustomerPrincipalIdForUpdate(CUSTOMER))
            .thenReturn(Optional.of(wallet));
    }

    private void givenExistingHold(EscrowHold.EscrowStatus status) {
        EscrowHold hold = new EscrowHold(
            42L, ORDER, CUSTOMER, MERCHANT, DRIVER, TOTAL, MERCHANT_AMOUNT, SHIPPING,
            status, Instant.now().plusSeconds(3600), Instant.now(), null
        );
        when(escrowRepository.findByOrderNumberForUpdate(ORDER)).thenReturn(Optional.of(hold));
        when(escrowRepository.findByOrderNumber(ORDER)).thenReturn(Optional.of(hold));
    }

    private void givenRecordedCreate(String fingerprint) {
        when(idempotencyRepository.find(eq(EscrowOperation.CREATE_HOLD), any()))
            .thenReturn(Optional.of(recordedCreate(fingerprint)));
    }

    private static EscrowIdempotencyRecord recordedCreate(String fingerprint) {
        return new EscrowIdempotencyRecord(
            7L, EscrowOperation.CREATE_HOLD, "order:" + ORDER, IdempotencyKeySource.DERIVED,
            ORDER, fingerprint, 42L, null, Instant.now()
        );
    }

    private static EscrowHold withId(EscrowHold hold, Long id) {
        return new EscrowHold(
            id, hold.orderNumber(), hold.customerPrincipalId(), hold.merchantPrincipalId(),
            hold.driverPrincipalId(), hold.totalOrderAmount(), hold.merchantAmount(),
            hold.shippingFeeAmount(), hold.status(), hold.autoReleaseAt(), hold.createdAt(),
            hold.releasedAt()
        );
    }

    private static PaymentTransaction argThatIsCheckoutLedgerRow() {
        return org.mockito.ArgumentMatchers.argThat(transaction ->
            transaction.type() == PaymentTransaction.TransactionType.CHECKOUT_PAYMENT
                && transaction.referenceNumber().equals("escrow:hold:" + ORDER)
                && transaction.principalId().equals(CUSTOMER)
                && transaction.amount().compareTo(TOTAL) == 0
        );
    }

    private static EscrowIdempotencyRecord argThatIsRefundTombstone() {
        return org.mockito.ArgumentMatchers.argThat(record ->
            record != null && record.isTombstone() && record.orderNumber().equals(ORDER)
        );
    }
}
