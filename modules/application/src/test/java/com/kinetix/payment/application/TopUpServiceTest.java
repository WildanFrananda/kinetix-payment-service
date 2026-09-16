package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.entity.PaymentTransaction.PaymentMethod;
import com.kinetix.payment.domain.entity.PaymentTransaction.TransactionStatus;
import com.kinetix.payment.domain.entity.PaymentTransaction.TransactionType;
import com.kinetix.payment.domain.exception.DuplicateIdempotencyKeyException;
import com.kinetix.payment.domain.exception.GatewayRefusedException;
import com.kinetix.payment.domain.exception.GatewayUnavailableException;
import com.kinetix.payment.domain.exception.IdempotencyConflictException;
import com.kinetix.payment.domain.exception.SettlementMismatchException;
import com.kinetix.payment.domain.exception.TopUpNotFoundException;
import com.kinetix.payment.domain.gateway.GatewayCharge;
import com.kinetix.payment.domain.gateway.GatewayChargeOutcome;
import com.kinetix.payment.domain.gateway.GatewayChargeRequest;
import com.kinetix.payment.domain.gateway.GatewaySettlement;
import com.kinetix.payment.domain.gateway.GatewayStatus;
import com.kinetix.payment.domain.gateway.PaymentInstructions;
import com.kinetix.payment.domain.gateway.VirtualAccountBank;
import com.kinetix.payment.domain.port.AdvisoryLockPort;
import com.kinetix.payment.domain.port.CustomerWalletRepositoryPort;
import com.kinetix.payment.domain.port.PaymentGatewayPort;
import com.kinetix.payment.domain.port.PaymentTransactionRepositoryPort;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TopUpServiceTest {
    private static final String CUSTOMER = "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7";
    private static final String KEY = "topup:7c1e2f0a";
    private static final String REFERENCE = "TOPUP-ref";
    private static final BigDecimal AMOUNT = new BigDecimal("50000.00");
    private static final String VA_RESPONSE = "{\"status_code\":\"201\",\"transaction_status\":\"pending\"}";

    private PaymentTransactionRepositoryPort transactions;
    private CustomerWalletRepositoryPort customerWallets;
    private PaymentGatewayPort gateway;
    private AdvisoryLockPort advisoryLock;
    private CountingTransactionRunner transactionRunner;
    private TopUpService service;

    @BeforeEach
    void setUp() {
        transactions = mock(PaymentTransactionRepositoryPort.class);
        customerWallets = mock(CustomerWalletRepositoryPort.class);
        gateway = mock(PaymentGatewayPort.class);
        advisoryLock = mock(AdvisoryLockPort.class);
        transactionRunner = new CountingTransactionRunner();

        when(transactions.save(any())).thenAnswer(call -> call.getArgument(0));
        when(customerWallets.save(any())).thenAnswer(call -> call.getArgument(0));
        when(transactions.findByPrincipalIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());

        service = new TopUpService(transactions, customerWallets, gateway, transactionRunner, advisoryLock);
    }

    @Test
    void theTopUpIsRecordedAsPendingBeforeTheGatewayIsAsked() {
        when(gateway.charge(any())).thenReturn(accepted());

        service.requestTopUp(vaTopUp());

        InOrder order = inOrder(transactions, gateway);
        order.verify(transactions).save(argThat((PaymentTransaction t) ->
            t.status() == TransactionStatus.PENDING && t.externalTransactionId() == null
        ));
        order.verify(gateway).charge(any());
    }

    @Test
    void anAcceptedChargeAnswersWithHowToPayAndDoesNotTouchAnyWallet() {
        when(gateway.charge(any())).thenReturn(accepted());

        TopUpOutcome outcome = service.requestTopUp(vaTopUp());

        assertEquals(TransactionStatus.PENDING, outcome.transaction().status());
        assertEquals("mid-tx-1", outcome.transaction().externalTransactionId());
        assertEquals("12345678901", outcome.instructions().virtualAccountNumber());
        assertFalse(outcome.replayed());
        verifyNoInteractions(customerWallets);
    }

    @Test
    void theChargeCarriesTheWholeRupiahAmountAndTheBank() {
        when(gateway.charge(any())).thenReturn(accepted());

        service.requestTopUp(vaTopUp());

        ArgumentCaptor<GatewayChargeRequest> sent = ArgumentCaptor.forClass(GatewayChargeRequest.class);
        verify(gateway).charge(sent.capture());
        assertEquals(50000L, sent.getValue().grossAmountRupiah());
        assertEquals(VirtualAccountBank.BCA, sent.getValue().bank());
        assertTrue(sent.getValue().referenceNumber().startsWith("TOPUP-"));
    }

    @Test
    void aRefusedChargeIsRecordedAsFailedAndReportedAsARefusal() {
        when(gateway.charge(any())).thenReturn(new GatewayCharge(
            GatewayChargeOutcome.REFUSED, null, PaymentInstructions.none(), "{\"status_code\":\"400\"}", "validation"
        ));

        assertThrows(GatewayRefusedException.class, () -> service.requestTopUp(vaTopUp()));

        verify(transactions).save(argThat((PaymentTransaction t) -> t.status() == TransactionStatus.FAILED));
        verifyNoInteractions(customerWallets);
    }

    @Test
    void aChargeWithNoDefiniteAnswerStaysPendingAndIsReportedAsUnavailableNotRefused() {
        when(gateway.charge(any())).thenReturn(new GatewayCharge(
            GatewayChargeOutcome.UNKNOWN, null, PaymentInstructions.none(), null, "timed out"
        ));

        GatewayUnavailableException unavailable =
            assertThrows(GatewayUnavailableException.class, () -> service.requestTopUp(vaTopUp()));

        assertTrue(unavailable.referenceNumber().startsWith("TOPUP-"));
        verify(transactions, never()).save(argThat((PaymentTransaction t) -> t.status() != TransactionStatus.PENDING));
        verifyNoInteractions(customerWallets);
    }

    @Test
    void aRepeatedKeyReplaysTheFirstTopUpWithoutASecondCharge() {
        PaymentTransaction first = charged("TOPUP-first", AMOUNT);
        when(transactions.findByPrincipalIdAndIdempotencyKey(CUSTOMER, KEY)).thenReturn(Optional.of(first));
        when(gateway.instructionsFrom(VA_RESPONSE))
            .thenReturn(new PaymentInstructions(VirtualAccountBank.BCA, "12345678901", null, null));

        TopUpOutcome outcome = service.requestTopUp(vaTopUp());

        assertTrue(outcome.replayed());
        assertEquals("TOPUP-first", outcome.transaction().referenceNumber());
        assertEquals("12345678901", outcome.instructions().virtualAccountNumber());
        verify(gateway, never()).charge(any());
        verify(transactions, never()).save(any());
    }

    @Test
    void aKeyReusedForADifferentAmountIsAConflictNotAReplay() {
        when(transactions.findByPrincipalIdAndIdempotencyKey(CUSTOMER, KEY))
            .thenReturn(Optional.of(charged("TOPUP-first", new BigDecimal("10000.00"))));

        assertThrows(IdempotencyConflictException.class, () -> service.requestTopUp(vaTopUp()));

        verify(gateway, never()).charge(any());
    }

    @Test
    void aReplayOfAChargeThatNeverGotAnAnswerIsStillUnavailableRatherThanAFreshCharge() {
        when(transactions.findByPrincipalIdAndIdempotencyKey(CUSTOMER, KEY)).thenReturn(Optional.of(
            PaymentTransaction.pendingTopUp("TOPUP-first", CUSTOMER, KEY, PaymentMethod.MIDTRANS_VA, AMOUNT)
        ));

        assertThrows(GatewayUnavailableException.class, () -> service.requestTopUp(vaTopUp()));

        verify(gateway, never()).charge(any());
    }

    @Test
    void aRaceOnTheSameKeyReplaysTheWinnerAndChargesNothing() {
        PaymentTransaction winner = charged("TOPUP-winner", AMOUNT);
        when(transactions.findByPrincipalIdAndIdempotencyKey(CUSTOMER, KEY))
            .thenReturn(Optional.empty(), Optional.of(winner));
        when(transactions.save(any())).thenThrow(new DuplicateIdempotencyKeyException("taken"));

        TopUpOutcome outcome = service.requestTopUp(vaTopUp());

        assertEquals("TOPUP-winner", outcome.transaction().referenceNumber());
        verify(gateway, never()).charge(any());
    }

    @Test
    void aFractionOfARupiahIsRefusedBeforeAnythingIsWritten() {
        assertThrows(IllegalArgumentException.class, () -> service.requestTopUp(new TopUpCommand(
            CUSTOMER, KEY, new BigDecimal("50000.50"), PaymentMethod.MIDTRANS_VA, VirtualAccountBank.BCA
        )));

        verifyNoInteractions(gateway);
        verify(transactions, never()).save(any());
    }

    @Test
    void aVirtualAccountNeedsABankQrisTakesNoneAndNothingElseIsATopUpMethod() {
        assertThrows(IllegalArgumentException.class, () -> service.requestTopUp(
            new TopUpCommand(CUSTOMER, KEY, AMOUNT, PaymentMethod.MIDTRANS_VA, null)
        ));
        assertThrows(IllegalArgumentException.class, () -> service.requestTopUp(
            new TopUpCommand(CUSTOMER, KEY, AMOUNT, PaymentMethod.MIDTRANS_QRIS, VirtualAccountBank.BCA)
        ));
        assertThrows(IllegalArgumentException.class, () -> service.requestTopUp(
            new TopUpCommand(CUSTOMER, KEY, AMOUNT, PaymentMethod.INTERNAL_WALLET, null)
        ));

        verifyNoInteractions(gateway);
    }

    @Test
    void anIdempotencyKeyIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> service.requestTopUp(
            new TopUpCommand(CUSTOMER, "", AMOUNT, PaymentMethod.MIDTRANS_VA, VirtualAccountBank.BCA)
        ));

        verifyNoInteractions(gateway);
    }

    @Test
    void aTopUpIsOnlyVisibleToTheCustomerWhoAskedForIt() {
        when(transactions.findByReferenceNumber(REFERENCE)).thenReturn(Optional.of(charged(REFERENCE, AMOUNT)));

        assertThrows(TopUpNotFoundException.class, () -> service.findTopUp("someone-else", REFERENCE));
    }

    @Test
    void aSettledTopUpCreditsTheRecordedAmountOnceInsideOneLockedTransaction() {
        recorded(charged(REFERENCE, AMOUNT));
        when(gateway.statusOf(REFERENCE)).thenReturn(gatewaySays(GatewaySettlement.SETTLED, "50000.00"));
        when(customerWallets.findByCustomerPrincipalIdForUpdate(CUSTOMER))
            .thenReturn(Optional.of(CustomerWallet.createInitial(CUSTOMER).topUp(new BigDecimal("1000.00"))));
        AtomicBoolean lockedInsideATransaction = new AtomicBoolean(false);
        doAnswer(call -> {
            lockedInsideATransaction.set(transactionRunner.insideTransaction());
            return null;
        }).when(advisoryLock).lockWalletOwner(CUSTOMER);

        SettlementOutcome outcome = service.settle(REFERENCE);

        assertTrue(outcome.credited());
        assertEquals(TransactionStatus.SUCCESS, outcome.transaction().status());
        ArgumentCaptor<CustomerWallet> wallet = ArgumentCaptor.forClass(CustomerWallet.class);
        verify(customerWallets).save(wallet.capture());
        assertEquals(0, new BigDecimal("51000.00").compareTo(wallet.getValue().balance()));
        assertEquals(1, transactionRunner.transactions());
        assertTrue(lockedInsideATransaction.get());
        InOrder order = inOrder(advisoryLock, transactions, customerWallets);
        order.verify(advisoryLock).lockWalletOwner(CUSTOMER);
        order.verify(transactions).findByReferenceNumberForUpdate(REFERENCE);
        order.verify(customerWallets).findByCustomerPrincipalIdForUpdate(CUSTOMER);
    }

    @Test
    void aSettlementForADifferentAmountCreditsNothing() {
        recorded(charged(REFERENCE, AMOUNT));
        when(gateway.statusOf(REFERENCE)).thenReturn(gatewaySays(GatewaySettlement.SETTLED, "5000000.00"));

        assertThrows(SettlementMismatchException.class, () -> service.settle(REFERENCE));

        verify(customerWallets, never()).save(any());
        verify(transactions, never()).save(any());
    }

    @Test
    void aTopUpAlreadySettledIsNeitherAskedAboutNorCreditedAgain() {
        recorded(charged(REFERENCE, AMOUNT).concludedAs(TransactionStatus.SUCCESS, "mid-tx-1", "{}"));

        SettlementOutcome outcome = service.settle(REFERENCE);

        assertFalse(outcome.credited());
        verifyNoInteractions(gateway, customerWallets);
    }

    @Test
    void aTopUpThatSettledWhileTheGatewayWasBeingAskedIsNotCreditedTwice() {
        when(transactions.findByReferenceNumber(REFERENCE)).thenReturn(Optional.of(charged(REFERENCE, AMOUNT)));
        when(transactions.findByReferenceNumberForUpdate(REFERENCE)).thenReturn(Optional.of(
            charged(REFERENCE, AMOUNT).concludedAs(TransactionStatus.SUCCESS, "mid-tx-1", "{}")
        ));
        when(gateway.statusOf(REFERENCE)).thenReturn(gatewaySays(GatewaySettlement.SETTLED, "50000.00"));

        SettlementOutcome outcome = service.settle(REFERENCE);

        assertFalse(outcome.credited());
        verify(customerWallets, never()).save(any());
    }

    @Test
    void aPaymentStillPendingChangesNothing() {
        recorded(charged(REFERENCE, AMOUNT));
        when(gateway.statusOf(REFERENCE)).thenReturn(gatewaySays(GatewaySettlement.PENDING, "50000.00"));

        SettlementOutcome outcome = service.settle(REFERENCE);

        assertEquals(TransactionStatus.PENDING, outcome.transaction().status());
        verify(transactions, never()).save(any());
        verify(customerWallets, never()).save(any());
    }

    @Test
    void anExpiredPaymentIsRecordedAndCreditsNothing() {
        recorded(charged(REFERENCE, AMOUNT));
        when(gateway.statusOf(REFERENCE)).thenReturn(gatewaySays(GatewaySettlement.EXPIRED, "50000.00"));

        SettlementOutcome outcome = service.settle(REFERENCE);

        assertEquals(TransactionStatus.EXPIRED, outcome.transaction().status());
        assertFalse(outcome.credited());
        verify(customerWallets, never()).save(any());
    }

    @Test
    void aGatewayThatCannotBeAskedDecidesNothing() {
        recorded(charged(REFERENCE, AMOUNT));
        when(gateway.statusOf(REFERENCE)).thenReturn(gatewaySays(GatewaySettlement.UNKNOWN, null));

        assertThrows(GatewayUnavailableException.class, () -> service.settle(REFERENCE));

        assertEquals(0, transactionRunner.transactions());
        verify(transactions, never()).save(any());
        verifyNoInteractions(customerWallets);
    }

    @Test
    void aGatewayWithNoSuchTransactionCreditsNothing() {
        recorded(charged(REFERENCE, AMOUNT));
        when(gateway.statusOf(REFERENCE)).thenReturn(gatewaySays(GatewaySettlement.NOT_FOUND, null));

        assertThrows(SettlementMismatchException.class, () -> service.settle(REFERENCE));

        verify(customerWallets, never()).save(any());
    }

    @Test
    void aReferenceThatIsNotATopUpIsNotFound() {
        when(transactions.findByReferenceNumber("escrow:hold:ORD-1")).thenReturn(Optional.of(new PaymentTransaction(
            null, "escrow:hold:ORD-1", null, CUSTOMER, TransactionType.CHECKOUT_PAYMENT,
            PaymentMethod.INTERNAL_WALLET, AMOUNT, TransactionStatus.SUCCESS, null, Instant.now(), null
        )));

        assertThrows(TopUpNotFoundException.class, () -> service.settle("escrow:hold:ORD-1"));

        verifyNoInteractions(gateway, customerWallets);
    }

    @Test
    void theSweepAsksTheGatewayAboutEachStaleTopUpAndCreditsTheOnesThatWerePaid() {
        stale(pendingTopUp("TOPUP-a"), pendingTopUp("TOPUP-b"));
        when(gateway.statusOf("TOPUP-a")).thenReturn(gatewaySays(GatewaySettlement.SETTLED, "50000.00"));
        when(gateway.statusOf("TOPUP-b")).thenReturn(gatewaySays(GatewaySettlement.SETTLED, "50000.00"));
        when(customerWallets.findByCustomerPrincipalIdForUpdate(CUSTOMER))
            .thenReturn(Optional.of(CustomerWallet.createInitial(CUSTOMER)));

        assertEquals(2, service.reconcilePendingTopUps(Duration.ofMinutes(10), 100));

        verify(transactions, times(2)).save(argThat((PaymentTransaction t) ->
            t.status() == TransactionStatus.SUCCESS
        ));
    }

    @Test
    void aTopUpTheGatewayCannotBeAskedAboutDoesNotStopTheRestOfTheSweep() {
        stale(pendingTopUp("TOPUP-unreachable"), pendingTopUp("TOPUP-paid"));
        when(gateway.statusOf("TOPUP-unreachable")).thenReturn(gatewaySays(GatewaySettlement.UNKNOWN, null));
        when(gateway.statusOf("TOPUP-paid")).thenReturn(gatewaySays(GatewaySettlement.SETTLED, "50000.00"));
        when(customerWallets.findByCustomerPrincipalIdForUpdate(CUSTOMER))
            .thenReturn(Optional.of(CustomerWallet.createInitial(CUSTOMER)));

        assertEquals(1, service.reconcilePendingTopUps(Duration.ofMinutes(10), 100));

        verify(transactions, never()).save(argThat((PaymentTransaction t) ->
            "TOPUP-unreachable".equals(t.referenceNumber()) && t.status() != TransactionStatus.PENDING
        ));
        verify(gateway).statusOf("TOPUP-paid");
    }

    @Test
    void aTopUpTheGatewayDisagreesAboutDoesNotStopTheRestOfTheSweep() {
        stale(pendingTopUp("TOPUP-mismatch"), pendingTopUp("TOPUP-paid"));
        when(gateway.statusOf("TOPUP-mismatch")).thenReturn(gatewaySays(GatewaySettlement.SETTLED, "1.00"));
        when(gateway.statusOf("TOPUP-paid")).thenReturn(gatewaySays(GatewaySettlement.SETTLED, "50000.00"));
        when(customerWallets.findByCustomerPrincipalIdForUpdate(CUSTOMER))
            .thenReturn(Optional.of(CustomerWallet.createInitial(CUSTOMER)));

        assertEquals(1, service.reconcilePendingTopUps(Duration.ofMinutes(10), 100));

        verify(gateway).statusOf("TOPUP-paid");
        verify(customerWallets, times(1)).save(any());
    }

    @Test
    void aTopUpTheGatewayStillCallsPendingIsLeftPendingAndNotCounted() {
        stale(pendingTopUp("TOPUP-a"));
        when(gateway.statusOf("TOPUP-a")).thenReturn(gatewaySays(GatewaySettlement.PENDING, null));

        assertEquals(0, service.reconcilePendingTopUps(Duration.ofMinutes(10), 100));

        verifyNoInteractions(customerWallets);
    }

    @Test
    void anExpiredTopUpIsConcludedWithoutCreditingAnything() {
        stale(pendingTopUp("TOPUP-a"));
        when(gateway.statusOf("TOPUP-a")).thenReturn(gatewaySays(GatewaySettlement.EXPIRED, null));

        assertEquals(1, service.reconcilePendingTopUps(Duration.ofMinutes(10), 100));

        verify(transactions).save(argThat((PaymentTransaction t) -> t.status() == TransactionStatus.EXPIRED));
        verify(customerWallets, never()).save(any());
    }

    @Test
    void theSweepAsksForTopUpsOlderThanTheCutoffAndNoMoreThanTheBatchSize() {
        when(transactions.findPendingTopUpsOlderThan(any(), anyInt())).thenReturn(List.of());

        Instant before = Instant.now();
        service.reconcilePendingTopUps(Duration.ofMinutes(10), 25);
        Instant after = Instant.now();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Integer> batch = ArgumentCaptor.forClass(Integer.class);
        verify(transactions).findPendingTopUpsOlderThan(cutoff.capture(), batch.capture());
        assertEquals(25, batch.getValue());
        assertFalse(cutoff.getValue().isBefore(before.minus(Duration.ofMinutes(10))));
        assertFalse(cutoff.getValue().isAfter(after.minus(Duration.ofMinutes(10))));
    }

    @Test
    void aSweepWithNothingStaleTouchesNothing() {
        when(transactions.findPendingTopUpsOlderThan(any(), anyInt())).thenReturn(List.of());

        assertEquals(0, service.reconcilePendingTopUps(Duration.ofMinutes(10), 100));

        verifyNoInteractions(gateway, customerWallets, advisoryLock);
    }

    private void stale(PaymentTransaction... pending) {
        when(transactions.findPendingTopUpsOlderThan(any(), anyInt())).thenReturn(List.of(pending));
        for (PaymentTransaction transaction : pending) {
            when(transactions.findByReferenceNumber(transaction.referenceNumber()))
                .thenReturn(Optional.of(transaction));
            when(transactions.findByReferenceNumberForUpdate(transaction.referenceNumber()))
                .thenReturn(Optional.of(transaction));
        }
    }

    private static PaymentTransaction pendingTopUp(String reference) {
        return charged(reference, AMOUNT);
    }

    private static TopUpCommand vaTopUp() {
        return new TopUpCommand(CUSTOMER, KEY, AMOUNT, PaymentMethod.MIDTRANS_VA, VirtualAccountBank.BCA);
    }

    private static GatewayCharge accepted() {
        return new GatewayCharge(
            GatewayChargeOutcome.ACCEPTED,
            "mid-tx-1",
            new PaymentInstructions(VirtualAccountBank.BCA, "12345678901", null, null),
            VA_RESPONSE,
            null
        );
    }

    private static PaymentTransaction charged(String reference, BigDecimal amount) {
        return PaymentTransaction.pendingTopUp(reference, CUSTOMER, KEY, PaymentMethod.MIDTRANS_VA, amount)
            .withGatewayResponse("mid-tx-1", VA_RESPONSE);
    }

    private void recorded(PaymentTransaction transaction) {
        when(transactions.findByReferenceNumber(REFERENCE)).thenReturn(Optional.of(transaction));
        when(transactions.findByReferenceNumberForUpdate(REFERENCE)).thenReturn(Optional.of(transaction));
    }

    private static GatewayStatus gatewaySays(GatewaySettlement settlement, String grossAmount) {
        return new GatewayStatus(
            settlement, grossAmount == null ? null : new BigDecimal(grossAmount), "mid-tx-1", "{}", null
        );
    }
}
