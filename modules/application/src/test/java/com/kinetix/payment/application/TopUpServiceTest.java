package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.AttentionReason;
import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.entity.PaymentTransaction.PaymentMethod;
import com.kinetix.payment.domain.entity.PaymentTransaction.TransactionStatus;
import com.kinetix.payment.domain.entity.PaymentTransaction.TransactionType;
import com.kinetix.payment.domain.exception.DuplicateIdempotencyKeyException;
import com.kinetix.payment.domain.exception.GatewayRefusedException;
import com.kinetix.payment.domain.exception.GatewayUnavailableException;
import com.kinetix.payment.domain.exception.IdempotencyConflictException;
import com.kinetix.payment.domain.exception.TopUpLimitExceededException;
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
import com.kinetix.payment.domain.port.TopUpAlarmPort;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
    private static final BigDecimal LIMIT = new BigDecimal("2000000");
    private static final Duration GRACE = Duration.ofHours(1);

    private PaymentTransactionRepositoryPort transactions;
    private CustomerWalletRepositoryPort customerWallets;
    private PaymentGatewayPort gateway;
    private AdvisoryLockPort advisoryLock;
    private CountingTransactionRunner transactionRunner;
    private TopUpAlarmPort alarm;
    private Map<String, PaymentTransaction> rows;
    private TopUpService service;

    @BeforeEach
    void setUp() {
        transactions = mock(PaymentTransactionRepositoryPort.class);
        customerWallets = mock(CustomerWalletRepositoryPort.class);
        gateway = mock(PaymentGatewayPort.class);
        advisoryLock = mock(AdvisoryLockPort.class);
        transactionRunner = new CountingTransactionRunner();
        alarm = mock(TopUpAlarmPort.class);
        rows = new HashMap<>();

        when(transactions.save(any())).thenAnswer(call -> {
            PaymentTransaction saved = call.getArgument(0);
            rows.put(saved.referenceNumber(), saved);
            return saved;
        });

        when(transactions.findByReferenceNumberForUpdate(anyString()))
            .thenAnswer(call -> Optional.ofNullable(rows.get(call.<String>getArgument(0))));
        when(customerWallets.save(any())).thenAnswer(call -> call.getArgument(0));
        when(transactions.findByPrincipalIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(transactions.pendingTopUpTotal(any())).thenReturn(BigDecimal.ZERO);

        service = new TopUpService(
            transactions,
            customerWallets,
            gateway,
            transactionRunner,
            advisoryLock,
            new TopUpLimits(LIMIT, LIMIT),
            alarm
        );
    }

    @Test
    void theTopUpIsRecordedAsPendingBeforeTheGatewayIsAsked() {
        when(gateway.charge(any())).thenReturn(accepted());

        service.requestTopUp(vaTopUp());

        InOrder order = inOrder(transactions, gateway);
        order.verify(transactions).save(
            argThat(
                (PaymentTransaction t) ->
                    t.status() == TransactionStatus.PENDING && t.externalTransactionId() == null
            )
        );
        order.verify(gateway).charge(any());
    }

    @Test
    void anAcceptedChargeAnswersWithHowToPayAndCreditsNothing() {
        when(gateway.charge(any())).thenReturn(accepted());

        TopUpOutcome outcome = service.requestTopUp(vaTopUp());

        assertEquals(TransactionStatus.PENDING, outcome.transaction().status());
        assertEquals("mid-tx-1", outcome.transaction().externalTransactionId());
        assertEquals("12345678901", outcome.instructions().virtualAccountNumber());
        assertFalse(outcome.replayed());
        verify(customerWallets, never()).save(any());
        verify(customerWallets, never()).findByCustomerPrincipalIdForUpdate(any());
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
            GatewayChargeOutcome.REFUSED,
            null,
            PaymentInstructions.none(),
            "{\"status_code\":\"400\"}",
            "validation"
        ));

        assertThrows(GatewayRefusedException.class, () -> service.requestTopUp(vaTopUp()));

        verify(transactions).save(argThat((PaymentTransaction t) -> t.status() == TransactionStatus.FAILED));
        verify(customerWallets, never()).save(any());
    }

    @Test
    void aChargeWithNoDefiniteAnswerStaysPendingAndIsReportedAsUnavailableNotRefused() {
        when(gateway.charge(any())).thenReturn(
            new GatewayCharge(
                GatewayChargeOutcome.UNKNOWN,
                null,
                PaymentInstructions.none(),
                null,
                "timed out"
            )
        );

        GatewayUnavailableException unavailable =
            assertThrows(GatewayUnavailableException.class, () -> service.requestTopUp(vaTopUp()));

        assertTrue(unavailable.referenceNumber().startsWith("TOPUP-"));
        verify(transactions, never()).save(argThat((PaymentTransaction t) -> t.status() != TransactionStatus.PENDING));
        verify(customerWallets, never()).save(any());
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
        when(transactions.findByPrincipalIdAndIdempotencyKey(CUSTOMER, KEY)).thenReturn(
            Optional.of(
                PaymentTransaction.pendingTopUp(
                    "TOPUP-first",
                    CUSTOMER,
                    KEY,
                    PaymentMethod.MIDTRANS_VA,
                    VirtualAccountBank.BCA,
                    AMOUNT
                )
            )
        );

        assertThrows(GatewayUnavailableException.class, () -> service.requestTopUp(vaTopUp()));

        verify(gateway, never()).charge(any());
    }

    @Test
    void aRaceOnTheSameKeyReplaysTheWinnerAndChargesNothing() {
        PaymentTransaction winner = charged("TOPUP-winner", AMOUNT);
        when(transactions.findByPrincipalIdAndIdempotencyKey(CUSTOMER, KEY))
            .thenReturn(Optional.empty(), Optional.empty(), Optional.of(winner));
        doThrow(new DuplicateIdempotencyKeyException("taken")).when(transactions).save(any());

        TopUpOutcome outcome = service.requestTopUp(vaTopUp());

        assertEquals("TOPUP-winner", outcome.transaction().referenceNumber());
        verify(gateway, never()).charge(any());
    }

    @Test
    void aFractionOfARupiahIsRefusedBeforeAnythingIsWritten() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.requestTopUp(
                new TopUpCommand(
                    CUSTOMER,
                    KEY,
                    new BigDecimal("50000.50"),
                    PaymentMethod.MIDTRANS_VA,
                    VirtualAccountBank.BCA
                )
            )
        );

        verifyNoInteractions(gateway);
        verify(transactions, never()).save(any());
    }

    @Test
    void aVirtualAccountNeedsABankQrisTakesNoneAndNothingElseIsATopUpMethod() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.requestTopUp(
                new TopUpCommand(CUSTOMER, KEY, AMOUNT, PaymentMethod.MIDTRANS_VA, null)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> service.requestTopUp(
                new TopUpCommand(CUSTOMER, KEY, AMOUNT, PaymentMethod.MIDTRANS_QRIS, VirtualAccountBank.BCA)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> service.requestTopUp(
                new TopUpCommand(CUSTOMER, KEY, AMOUNT, PaymentMethod.INTERNAL_WALLET, null)
            )
        );

        verifyNoInteractions(gateway);
    }

    @Test
    void anIdempotencyKeyIsRequired() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.requestTopUp(
                new TopUpCommand(CUSTOMER, "", AMOUNT, PaymentMethod.MIDTRANS_VA, VirtualAccountBank.BCA)
            )
        );

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
    void aSettlementForADifferentAmountIsSetAsideForAttentionAndCreditsNothing() {
        recorded(charged(REFERENCE, AMOUNT));
        when(gateway.statusOf(REFERENCE)).thenReturn(gatewaySays(GatewaySettlement.SETTLED, "5000000.00"));

        SettlementOutcome outcome = service.settle(REFERENCE);

        assertFalse(outcome.credited());
        assertEquals(TransactionStatus.NEEDS_ATTENTION, outcome.transaction().status());
        assertEquals(AttentionReason.AMOUNT_MISMATCH, outcome.transaction().attentionReason());
        verify(customerWallets, never()).save(any());
        verify(alarm).setAside(outcome.transaction());
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

        verify(transactions).markChecked(eq(REFERENCE), any());
        verify(transactions, never()).save(any());
        verify(advisoryLock, never()).lockWalletOwner(any());
        verifyNoInteractions(customerWallets);
    }

    @Test
    void everyAnswerTheGatewayGivesIsRecordedAsACheckSoTheSweepMovesOn() {
        stale(charged("TOPUP-abandoned", AMOUNT));
        when(gateway.statusOf("TOPUP-abandoned")).thenReturn(gatewaySays(GatewaySettlement.PENDING, null));

        service.reconcilePendingTopUps(Duration.ofMinutes(10), 100, GRACE);

        verify(transactions).markChecked(eq("TOPUP-abandoned"), any());
        verify(transactions, never()).save(any());
    }

    @Test
    void aNotificationForATransactionTheGatewayDoesNotKnowLeavesItPendingAndCreditsNothing() {
        recorded(charged(REFERENCE, AMOUNT));
        when(gateway.statusOf(REFERENCE)).thenReturn(gatewaySays(GatewaySettlement.NOT_FOUND, null));

        SettlementOutcome outcome = service.settle(REFERENCE);

        assertEquals(TransactionStatus.PENDING, outcome.transaction().status());
        verify(transactions, never()).save(any());
        verify(customerWallets, never()).save(any());
    }

    @Test
    void aReferenceThatIsNotATopUpIsNotFound() {
        when(transactions.findByReferenceNumber("escrow:hold:ORD-1")).thenReturn(
            Optional.of(
                new PaymentTransaction(
                    null,
                    "escrow:hold:ORD-1",
                    null,
                    CUSTOMER,
                    TransactionType.CHECKOUT_PAYMENT,
                    PaymentMethod.INTERNAL_WALLET,
                    AMOUNT,
                    TransactionStatus.SUCCESS,
                    null,
                    Instant.now(),
                    null,
                    null,
                    null
                )
            )
        );

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

        assertEquals(2, service.reconcilePendingTopUps(Duration.ofMinutes(10), 100, GRACE));

        verify(transactions, times(2)).save(
            argThat(
                (PaymentTransaction t) ->
                    t.status() == TransactionStatus.SUCCESS
            )
        );
    }

    @Test
    void aTopUpTheGatewayCannotBeAskedAboutDoesNotStopTheRestOfTheSweep() {
        stale(pendingTopUp("TOPUP-unreachable"), pendingTopUp("TOPUP-paid"));
        when(gateway.statusOf("TOPUP-unreachable")).thenReturn(gatewaySays(GatewaySettlement.UNKNOWN, null));
        when(gateway.statusOf("TOPUP-paid")).thenReturn(gatewaySays(GatewaySettlement.SETTLED, "50000.00"));
        when(customerWallets.findByCustomerPrincipalIdForUpdate(CUSTOMER))
            .thenReturn(Optional.of(CustomerWallet.createInitial(CUSTOMER)));

        assertEquals(1, service.reconcilePendingTopUps(Duration.ofMinutes(10), 100, GRACE));

        verify(transactions, never()).save(
            argThat(
                (PaymentTransaction t) ->
                    "TOPUP-unreachable".equals(t.referenceNumber()) && t.status() != TransactionStatus.PENDING
            )
        );
        verify(gateway).statusOf("TOPUP-paid");
    }

    @Test
    void aTopUpTheGatewayDisagreesAboutDoesNotStopTheRestOfTheSweep() {
        stale(pendingTopUp("TOPUP-mismatch"), pendingTopUp("TOPUP-paid"));
        when(gateway.statusOf("TOPUP-mismatch")).thenReturn(gatewaySays(GatewaySettlement.SETTLED, "1.00"));
        when(gateway.statusOf("TOPUP-paid")).thenReturn(gatewaySays(GatewaySettlement.SETTLED, "50000.00"));
        when(customerWallets.findByCustomerPrincipalIdForUpdate(CUSTOMER))
            .thenReturn(Optional.of(CustomerWallet.createInitial(CUSTOMER)));

        assertEquals(2, service.reconcilePendingTopUps(Duration.ofMinutes(10), 100, GRACE));

        verify(gateway).statusOf("TOPUP-paid");
        verify(customerWallets, times(1)).save(any());
        verify(transactions).save(
            argThat(
                (PaymentTransaction t) ->
                    "TOPUP-mismatch".equals(t.referenceNumber()) && t.status() == TransactionStatus.NEEDS_ATTENTION
            )
        );
    }

    @Test
    void aTopUpTheGatewayStillCallsPendingIsLeftPendingAndNotCounted() {
        stale(pendingTopUp("TOPUP-a"));
        when(gateway.statusOf("TOPUP-a")).thenReturn(gatewaySays(GatewaySettlement.PENDING, null));

        assertEquals(0, service.reconcilePendingTopUps(Duration.ofMinutes(10), 100, GRACE));

        verifyNoInteractions(customerWallets);
    }

    @Test
    void anExpiredTopUpIsConcludedWithoutCreditingAnything() {
        stale(pendingTopUp("TOPUP-a"));
        when(gateway.statusOf("TOPUP-a")).thenReturn(gatewaySays(GatewaySettlement.EXPIRED, null));

        assertEquals(1, service.reconcilePendingTopUps(Duration.ofMinutes(10), 100, GRACE));

        verify(transactions).save(argThat((PaymentTransaction t) -> t.status() == TransactionStatus.EXPIRED));
        verify(customerWallets, never()).save(any());
    }

    @Test
    void theSweepAsksForTopUpsOlderThanTheCutoffAndNoMoreThanTheBatchSize() {
        when(transactions.findPendingTopUpsOlderThan(any(), anyInt())).thenReturn(List.of());

        Instant before = Instant.now();
        service.reconcilePendingTopUps(Duration.ofMinutes(10), 25, GRACE);
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

        assertEquals(0, service.reconcilePendingTopUps(Duration.ofMinutes(10), 100, GRACE));

        verifyNoInteractions(gateway, customerWallets, advisoryLock);
    }

    @Test
    void aTopUpAboveTheLimitIsRefusedBeforeAnythingIsWrittenOrCharged() {
        TopUpLimitExceededException refused = assertThrows(
            TopUpLimitExceededException.class,
            () -> service.requestTopUp(vaTopUp(new BigDecimal("2000001")))
        );

        assertEquals(TopUpLimitExceededException.TOP_UP_AMOUNT, refused.code());
        assertEquals(0, LIMIT.compareTo(refused.limit()));
        verifyNoInteractions(gateway);
        verify(transactions, never()).save(any());
    }

    @Test
    void aTopUpOfExactlyTheLimitIntoAnEmptyWalletIsCharged() {
        when(gateway.charge(any())).thenReturn(accepted());

        service.requestTopUp(vaTopUp(LIMIT));

        verify(gateway).charge(any());
    }

    @Test
    void aTopUpThatWouldTakeTheBalanceOverTheLimitIsRefusedBeforeTheGatewayIsCalled() {
        walletHolds("1950000.00");

        TopUpLimitExceededException refused = assertThrows(
            TopUpLimitExceededException.class,
            () -> service.requestTopUp(vaTopUp(new BigDecimal("50001")))
        );

        assertEquals(TopUpLimitExceededException.WALLET_BALANCE, refused.code());
        verifyNoInteractions(gateway);
        verify(transactions, never()).save(any());
    }

    @Test
    void topUpsStillAwaitingPaymentCountAgainstTheWalletLimit() {
        walletHolds("500000.00");
        when(transactions.pendingTopUpTotal(CUSTOMER)).thenReturn(new BigDecimal("1450000.00"));

        TopUpLimitExceededException refused = assertThrows(
            TopUpLimitExceededException.class,
            () -> service.requestTopUp(vaTopUp(new BigDecimal("50001")))
        );

        assertEquals(TopUpLimitExceededException.WALLET_BALANCE, refused.code());
        assertTrue(
            refused.getMessage().contains("IDR 1,450,000 in top-ups still awaiting payment"),
            refused.getMessage()
        );
        verifyNoInteractions(gateway);
        verify(transactions, never()).save(any());
    }

    @Test
    void aTopUpThatExactlyFillsWhatTheBalanceAndTheTopUpsAwaitingPaymentLeaveIsCharged() {
        walletHolds("500000.00");
        when(transactions.pendingTopUpTotal(CUSTOMER)).thenReturn(new BigDecimal("1450000.00"));
        when(gateway.charge(any())).thenReturn(accepted());

        service.requestTopUp(vaTopUp(new BigDecimal("50000")));

        verify(gateway).charge(any());
    }

    @Test
    void theRoomIsCheckedAndThePendingTopUpRecordedInOneTransactionUnderTheWalletOwnersLock() {
        List<Integer> transactionOf = new ArrayList<>();
        doAnswer(call -> {
            transactionOf.add(transactionRunner.insideTransaction() ? transactionRunner.transactions() : -1);
            return null;
        }).when(advisoryLock).lockWalletOwner(CUSTOMER);
        when(transactions.pendingTopUpTotal(CUSTOMER)).thenAnswer(call -> {
            transactionOf.add(transactionRunner.insideTransaction() ? transactionRunner.transactions() : -1);
            return BigDecimal.ZERO;
        });
        doAnswer(call -> {
            transactionOf.add(transactionRunner.insideTransaction() ? transactionRunner.transactions() : -1);
            PaymentTransaction saved = call.getArgument(0);
            rows.put(saved.referenceNumber(), saved);
            return saved;
        }).when(transactions).save(any());
        AtomicBoolean chargedInsideATransaction = new AtomicBoolean(true);
        when(gateway.charge(any())).thenAnswer(call -> {
            chargedInsideATransaction.set(transactionRunner.insideTransaction());
            return accepted();
        });

        service.requestTopUp(vaTopUp());

        assertEquals(
            List.of(1, 1, 1, 2),
            transactionOf,
            "the lock, the room check and the insert must share one transaction, and the gateway's reply its own"
        );
        assertFalse(chargedInsideATransaction.get(), "the gateway was called while the wallet owner's lock was held");
        InOrder order = inOrder(advisoryLock, customerWallets, transactions, gateway);
        order.verify(advisoryLock).lockWalletOwner(CUSTOMER);
        order.verify(transactions).findByPrincipalIdAndIdempotencyKey(CUSTOMER, KEY);
        order.verify(customerWallets).findByCustomerPrincipalId(CUSTOMER);
        order.verify(transactions).pendingTopUpTotal(CUSTOMER);
        order.verify(transactions).save(argThat((PaymentTransaction t) -> t.status() == TransactionStatus.PENDING));
        order.verify(gateway).charge(any());
    }

    @Test
    void aRetryThatFindsItsFirstAttemptOnceItHoldsTheLockIsReplayedNotRefused() {
        PaymentTransaction first = charged("TOPUP-first", AMOUNT);
        when(transactions.findByPrincipalIdAndIdempotencyKey(CUSTOMER, KEY))
            .thenReturn(Optional.empty(), Optional.of(first));
        when(transactions.pendingTopUpTotal(CUSTOMER)).thenReturn(LIMIT);

        TopUpOutcome outcome = service.requestTopUp(vaTopUp());

        assertTrue(outcome.replayed());
        assertEquals("TOPUP-first", outcome.transaction().referenceNumber());
        verify(transactions, never()).save(any());
        verify(gateway, never()).charge(any());
    }

    @Test
    void aReplayIsAnsweredEvenWhenItsOwnCreditFilledTheWallet() {
        walletHolds("2000000.00");
        PaymentTransaction first = charged("TOPUP-first", AMOUNT)
            .concludedAs(TransactionStatus.SUCCESS, "mid-tx-1", "{}");
        when(transactions.findByPrincipalIdAndIdempotencyKey(CUSTOMER, KEY)).thenReturn(Optional.of(first));

        TopUpOutcome outcome = service.requestTopUp(vaTopUp());

        assertTrue(outcome.replayed());
        assertEquals(TransactionStatus.SUCCESS, outcome.transaction().status());
        verifyNoInteractions(gateway);
    }

    @Test
    void aPaidTopUpIsCreditedInFullEvenWhenARefundFilledTheWalletWhileItAwaitedPayment() {
        recorded(charged(REFERENCE, AMOUNT));
        walletHolds("1980000.00");
        when(gateway.statusOf(REFERENCE)).thenReturn(gatewaySays(GatewaySettlement.SETTLED, "50000.00"));

        SettlementOutcome outcome = service.settle(REFERENCE);

        assertTrue(outcome.credited());
        assertEquals(TransactionStatus.SUCCESS, outcome.transaction().status());
        assertNull(outcome.transaction().attentionReason());
        ArgumentCaptor<CustomerWallet> wallet = ArgumentCaptor.forClass(CustomerWallet.class);
        verify(customerWallets).save(wallet.capture());
        assertEquals(0, new BigDecimal("2030000.00").compareTo(wallet.getValue().balance()));
        verifyNoInteractions(alarm);
    }

    @Test
    void aWalletAboveTheLimitAfterACreditTakesNoNewTopUp() {
        walletHolds("2030000.00");

        TopUpLimitExceededException refused = assertThrows(
            TopUpLimitExceededException.class,
            () -> service.requestTopUp(vaTopUp(new BigDecimal("1000")))
        );

        assertEquals(TopUpLimitExceededException.WALLET_BALANCE, refused.code());
        verifyNoInteractions(gateway);
        verify(transactions, never()).save(any());
    }

    @Test
    void aTopUpSetAsideForAttentionIsNeitherAskedAboutNorCreditedAgain() {
        recorded(
            charged(REFERENCE, AMOUNT).setAsideFor(AttentionReason.AMOUNT_MISMATCH, "mid-tx-1", "{}")
        );

        SettlementOutcome outcome = service.settle(REFERENCE);

        assertEquals(TransactionStatus.NEEDS_ATTENTION, outcome.transaction().status());
        verifyNoInteractions(gateway, customerWallets, alarm);
    }

    @Test
    void aChargeThatNeverGotAnAnswerAndIsStillUnknownAfterTheGraceIsConcludedAsFailed() {
        PaymentTransaction neverAnswered = createdAgo(
            PaymentTransaction.pendingTopUp(
                "TOPUP-lost",
                CUSTOMER,
                KEY,
                PaymentMethod.MIDTRANS_VA,
                VirtualAccountBank.BCA,
                AMOUNT
            ),
            GRACE.plusMinutes(1)
        );
        stale(neverAnswered);
        when(gateway.statusOf("TOPUP-lost")).thenReturn(gatewaySays(GatewaySettlement.NOT_FOUND, null));

        assertEquals(1, service.reconcilePendingTopUps(Duration.ofMinutes(10), 100, GRACE));

        assertEquals(TransactionStatus.FAILED, rows.get("TOPUP-lost").status());
        verify(customerWallets, never()).save(any());
        verifyNoInteractions(alarm);
    }

    @Test
    void aChargeTheGatewayAcceptedAndNoLongerKnowsIsSetAsideNotFailed() {
        stale(createdAgo(charged("TOPUP-forgotten", AMOUNT), GRACE.plusMinutes(1)));
        when(gateway.statusOf("TOPUP-forgotten")).thenReturn(gatewaySays(GatewaySettlement.NOT_FOUND, null));

        assertEquals(1, service.reconcilePendingTopUps(Duration.ofMinutes(10), 100, GRACE));

        PaymentTransaction setAside = rows.get("TOPUP-forgotten");
        assertEquals(TransactionStatus.NEEDS_ATTENTION, setAside.status());
        assertEquals(AttentionReason.GATEWAY_HAS_NO_RECORD, setAside.attentionReason());
        verify(alarm).setAside(setAside);
    }

    @Test
    void aChargeTheGatewayDoesNotKnowYetStaysPendingInsideTheGrace() {
        stale(createdAgo(charged("TOPUP-young", AMOUNT), Duration.ofMinutes(15)));
        when(gateway.statusOf("TOPUP-young")).thenReturn(gatewaySays(GatewaySettlement.NOT_FOUND, null));

        assertEquals(0, service.reconcilePendingTopUps(Duration.ofMinutes(10), 100, GRACE));

        verify(transactions, never()).save(any());
    }

    @Test
    void aSweepOfAHundredMismatchedTopUpsTakesEveryOneOutOfTheSweep() {
        PaymentTransaction[] mismatched = new PaymentTransaction[100];
        for (int i = 0; i < mismatched.length; i++) {
            mismatched[i] = charged("TOPUP-m" + i, AMOUNT);
            when(gateway.statusOf("TOPUP-m" + i)).thenReturn(gatewaySays(GatewaySettlement.SETTLED, "1.00"));
        }
        stale(mismatched);

        assertEquals(100, service.reconcilePendingTopUps(Duration.ofMinutes(10), 100, GRACE));

        assertTrue(
            rows.values().stream().allMatch(row -> row.status() == TransactionStatus.NEEDS_ATTENTION),
            "a mismatched top-up stayed PENDING and would be swept again"
        );
        verify(customerWallets, never()).save(any());
    }

    @Test
    void aKeyReusedForAnotherBankIsAConflictNotAReplay() {
        when(transactions.findByPrincipalIdAndIdempotencyKey(CUSTOMER, KEY))
            .thenReturn(Optional.of(charged("TOPUP-first", AMOUNT)));

        assertThrows(
            IdempotencyConflictException.class,
            () -> service.requestTopUp(
                new TopUpCommand(CUSTOMER, KEY, AMOUNT, PaymentMethod.MIDTRANS_VA, VirtualAccountBank.BNI)
            )
        );

        verify(gateway, never()).charge(any());
    }

    @Test
    void theBankIsRecordedWithTheTopUp() {
        when(gateway.charge(any())).thenReturn(accepted());

        service.requestTopUp(vaTopUp());

        verify(transactions).save(
            argThat(
                (PaymentTransaction t) ->
                    t.status() == TransactionStatus.PENDING
                        && t.externalTransactionId() == null
                        && t.bank() == VirtualAccountBank.BCA
            )
        );
    }

    @Test
    void anAcceptedChargeWithoutATransactionIdIsReplayedWithHowToPayRatherThanAs503() {
        PaymentTransaction acceptedWithoutId = PaymentTransaction.pendingTopUp(
            "TOPUP-first",
            CUSTOMER,
            KEY,
            PaymentMethod.MIDTRANS_VA,
            VirtualAccountBank.BCA,
            AMOUNT
        )
            .withGatewayResponse(null, VA_RESPONSE);
        when(transactions.findByPrincipalIdAndIdempotencyKey(CUSTOMER, KEY))
            .thenReturn(Optional.of(acceptedWithoutId));
        when(gateway.instructionsFrom(VA_RESPONSE))
            .thenReturn(new PaymentInstructions(VirtualAccountBank.BCA, "12345678901", null, null));

        TopUpOutcome outcome = service.requestTopUp(vaTopUp());

        assertTrue(outcome.replayed());
        assertEquals("12345678901", outcome.instructions().virtualAccountNumber());
        verify(gateway, never()).charge(any());
    }

    @Test
    void anAcceptedChargeDoesNotOverwriteATopUpTheNotificationAlreadySettled() {
        when(gateway.charge(any())).thenAnswer(call -> {
            GatewayChargeRequest request = call.getArgument(0);
            rows.put(
                request.referenceNumber(),
                rows.get(request.referenceNumber()).concludedAs(TransactionStatus.SUCCESS, "mid-tx-1", "{}")
            );
            return accepted();
        });

        TopUpOutcome outcome = service.requestTopUp(vaTopUp());

        assertEquals(TransactionStatus.SUCCESS, outcome.transaction().status());
        assertEquals(PaymentInstructions.none(), outcome.instructions());
        verify(transactions, times(1)).save(any());
    }

    @Test
    void theRecordedAmountCarriesTheLedgerScaleWhateverScaleTheRequestUsed() {
        when(gateway.charge(any())).thenReturn(accepted());

        TopUpOutcome outcome = service.requestTopUp(vaTopUp(new BigDecimal("50000")));

        assertEquals("50000.00", outcome.transaction().amount().toPlainString());
    }

    private void stale(PaymentTransaction... pending) {
        when(transactions.findPendingTopUpsOlderThan(any(), anyInt())).thenReturn(List.of(pending));
        for (PaymentTransaction transaction : pending) {
            recorded(transaction);
        }
    }

    private static PaymentTransaction pendingTopUp(String reference) {
        return charged(reference, AMOUNT);
    }

    private static TopUpCommand vaTopUp() {
        return vaTopUp(AMOUNT);
    }

    private static TopUpCommand vaTopUp(BigDecimal amount) {
        return new TopUpCommand(CUSTOMER, KEY, amount, PaymentMethod.MIDTRANS_VA, VirtualAccountBank.BCA);
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
        return PaymentTransaction.pendingTopUp(
            reference,
            CUSTOMER,
            KEY,
            PaymentMethod.MIDTRANS_VA,
            VirtualAccountBank.BCA,
            amount
        )
            .withGatewayResponse("mid-tx-1", VA_RESPONSE);
    }

    private void recorded(PaymentTransaction transaction) {
        when(transactions.findByReferenceNumber(transaction.referenceNumber())).thenReturn(Optional.of(transaction));
        rows.put(transaction.referenceNumber(), transaction);
    }

    private static PaymentTransaction createdAgo(PaymentTransaction transaction, Duration age) {
        return new PaymentTransaction(
            transaction.id(),
            transaction.referenceNumber(),
            transaction.externalTransactionId(),
            transaction.principalId(),
            transaction.type(),
            transaction.method(),
            transaction.amount(),
            transaction.status(),
            transaction.gatewayResponse(),
            Instant.now().minus(age),
            transaction.idempotencyKey(),
            transaction.bank(),
            transaction.attentionReason()
        );
    }

    private void walletHolds(String balance) {
        CustomerWallet wallet = CustomerWallet.createInitial(CUSTOMER).topUp(new BigDecimal(balance));
        when(customerWallets.findByCustomerPrincipalId(CUSTOMER)).thenReturn(Optional.of(wallet));
        when(customerWallets.findByCustomerPrincipalIdForUpdate(CUSTOMER)).thenReturn(Optional.of(wallet));
    }

    private static GatewayStatus gatewaySays(GatewaySettlement settlement, String grossAmount) {
        return new GatewayStatus(
            settlement,
            grossAmount == null ? null : new BigDecimal(grossAmount),
            "mid-tx-1",
            "{}",
            null
        );
    }
}
