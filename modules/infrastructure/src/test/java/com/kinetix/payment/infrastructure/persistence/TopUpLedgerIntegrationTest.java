package com.kinetix.payment.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kinetix.payment.application.SettlementOutcome;
import com.kinetix.payment.application.TopUpCommand;
import com.kinetix.payment.application.TopUpLimits;
import com.kinetix.payment.application.TopUpService;
import com.kinetix.payment.domain.entity.AttentionReason;
import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.entity.PaymentTransaction.PaymentMethod;
import com.kinetix.payment.domain.entity.PaymentTransaction.TransactionStatus;
import com.kinetix.payment.domain.exception.IdempotencyConflictException;
import com.kinetix.payment.domain.exception.TopUpLimitExceededException;
import com.kinetix.payment.domain.gateway.GatewayCharge;
import com.kinetix.payment.domain.gateway.GatewayChargeOutcome;
import com.kinetix.payment.domain.gateway.GatewayChargeRequest;
import com.kinetix.payment.domain.gateway.GatewaySettlement;
import com.kinetix.payment.domain.gateway.GatewayStatus;
import com.kinetix.payment.domain.gateway.PaymentInstructions;
import com.kinetix.payment.domain.gateway.VirtualAccountBank;
import com.kinetix.payment.domain.port.AdvisoryLockPort;
import com.kinetix.payment.domain.port.PaymentGatewayPort;
import com.kinetix.payment.domain.port.TransactionRunnerPort;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = PersistenceTestApplication.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_DB_URL", matches = ".+")
@TestPropertySource(properties = {
    "spring.datasource.url=${PAYMENT_TEST_DB_URL}",
    "spring.datasource.username=${PAYMENT_TEST_DB_USERNAME}",
    "spring.datasource.password=${PAYMENT_TEST_DB_PASSWORD}",
    "spring.liquibase.enabled=false",
    "spring.jpa.hibernate.ddl-auto=validate"
})
class TopUpLedgerIntegrationTest {
    private static final BigDecimal AMOUNT = new BigDecimal("150000.00");

    private static final BigDecimal LIMIT = new BigDecimal("2000000");

    @Autowired private TransactionRunnerPort transactionRunner;
    @Autowired private AdvisoryLockPort advisoryLock;
    @Autowired private CustomerWalletAdapter customerWalletRepository;
    @Autowired private PaymentTransactionAdapter paymentTransactionRepository;
    @Autowired private PaymentTransactionJpaRepository jpaRepository;

    private final List<PaymentTransaction> alarms = new CopyOnWriteArrayList<>();

    @Test
    void aNotificationThatSettlesBeforeTheChargeReplyIsSavedIsNeitherRevertedNorCreditedTwice() {
        String customer = principal();
        AtomicReference<TopUpService> service = new AtomicReference<>();
        service.set(
            topUps(
                new ScriptedGateway(
                    request -> {
                        service.get().settle(request.referenceNumber());
                        return accepted(request);
                    },
                    reference -> settled(reference, AMOUNT)
                )
            )
        );

        String reference = service.get().requestTopUp(vaTopUp(customer, AMOUNT)).transaction().referenceNumber();
        TransactionStatus afterTheReply = paymentTransactionRepository.findByReferenceNumber(reference)
            .orElseThrow()
            .status();
        service.get().settle(reference);
        BigDecimal balance = balanceOf(customer);

        assertAll(
            () -> assertEquals(
                TransactionStatus.SUCCESS,
                afterTheReply,
                "the charge reply overwrote a settled top-up with its stale PENDING copy"
            ),
            () -> assertEquals(
                0,
                AMOUNT.compareTo(balance),
                "one paid top-up of " + AMOUNT + " left the wallet holding " + balance
            )
        );
    }

    @Test
    void theFirstAnswerCarriesTheAmountTheWayTheLedgerStoresIt() {
        TopUpService service = topUps(new ScriptedGateway(TopUpLedgerIntegrationTest::accepted, reference -> null));

        PaymentTransaction first = service.requestTopUp(vaTopUp(principal(), new BigDecimal("150000")))
            .transaction();
        PaymentTransaction stored = paymentTransactionRepository.findByReferenceNumber(first.referenceNumber())
            .orElseThrow();

        assertEquals(stored.amount().toPlainString(), first.amount().toPlainString());
        assertEquals("150000.00", first.amount().toPlainString());
    }

    @Test
    void theBankIsStoredAndAReplayForAnotherBankIsAConflict() {
        TopUpService service = topUps(new ScriptedGateway(TopUpLedgerIntegrationTest::accepted, reference -> null));
        String customer = principal();
        String key = "bank:" + UUID.randomUUID();

        String reference = service.requestTopUp(
            new TopUpCommand(customer, key, AMOUNT, PaymentMethod.MIDTRANS_VA, VirtualAccountBank.BRI)
        ).transaction().referenceNumber();

        assertEquals(
            VirtualAccountBank.BRI,
            paymentTransactionRepository.findByReferenceNumber(reference).orElseThrow().bank()
        );
        assertThrows(
            IdempotencyConflictException.class,
            () -> service.requestTopUp(
                new TopUpCommand(customer, key, AMOUNT, PaymentMethod.MIDTRANS_VA, VirtualAccountBank.BNI)
            )
        );
    }

    @Test
    void aTopUpThatWouldOverfillTheWalletIsRefusedBeforeAnythingIsRecorded() {
        String customer = principal();
        seedCustomer(customer, new BigDecimal("1950000.00"));
        String key = "full:" + UUID.randomUUID();
        TopUpService service = topUps(
            new ScriptedGateway(
                request -> {
                    throw new AssertionError("the gateway was asked to charge a top-up the wallet has no room for");
                },
                reference -> null
            )
        );

        assertThrows(
            TopUpLimitExceededException.class,
            () -> service.requestTopUp(
                new TopUpCommand(customer, key, AMOUNT, PaymentMethod.MIDTRANS_VA, VirtualAccountBank.BCA)
            )
        );

        assertTrue(paymentTransactionRepository.findByPrincipalIdAndIdempotencyKey(customer, key).isEmpty());
    }

    @Test
    void aSettlementThatWouldOverfillTheWalletIsSetAsideCreditsNothingAndLeavesTheSweep() {
        String customer = principal();
        seedCustomer(customer, new BigDecimal("1900000.00"));
        String reference = pendingTopUp(customer, AMOUNT);
        TopUpService service = topUps(new ScriptedGateway(request -> null, ref -> settled(ref, AMOUNT)));

        SettlementOutcome outcome = service.settle(reference);

        PaymentTransaction stored = paymentTransactionRepository.findByReferenceNumber(reference).orElseThrow();
        List<String> swept = paymentTransactionRepository
            .findPendingTopUpsOlderThan(Instant.now().plusSeconds(1), 100_000)
            .stream()
            .map(PaymentTransaction::referenceNumber)
            .toList();
        assertAll(
            () -> assertFalse(outcome.credited()),
            () -> assertEquals(TransactionStatus.NEEDS_ATTENTION, stored.status()),
            () -> assertEquals(AttentionReason.BALANCE_LIMIT_EXCEEDED, stored.attentionReason()),
            () -> assertEquals(0, new BigDecimal("1900000.00").compareTo(balanceOf(customer))),
            () -> assertEquals(List.of(reference), alarms.stream().map(PaymentTransaction::referenceNumber).toList()),
            () -> assertFalse(swept.contains(reference), "a top-up set aside for attention is still swept")
        );
    }

    @Test
    void aLostNotificationIsReachedWhileOlderAbandonedTopUpsStillFillTheBatch() {
        String customer = principal();
        List<String> abandoned = List.of(
            pendingTopUpCreatedAt(customer, Instant.parse("2000-01-01T00:00:01Z")),
            pendingTopUpCreatedAt(customer, Instant.parse("2000-01-01T00:00:02Z")),
            pendingTopUpCreatedAt(customer, Instant.parse("2000-01-01T00:00:03Z"))
        );
        String lost = pendingTopUpCreatedAt(customer, Instant.parse("2000-01-01T00:00:10Z"));
        TopUpService service = topUps(
            new ScriptedGateway(
                request -> null,
                reference -> reference.equals(lost) ? settled(reference, AMOUNT) : stillPending(reference)
            )
        );

        service.reconcilePendingTopUps(Duration.ZERO, abandoned.size(), Duration.ofHours(1));
        service.reconcilePendingTopUps(Duration.ZERO, abandoned.size(), Duration.ofHours(1));

        assertAll(
            () -> assertEquals(
                TransactionStatus.SUCCESS,
                paymentTransactionRepository.findByReferenceNumber(lost).orElseThrow().status(),
                "two sweeps of " + abandoned.size() + " asked only the older abandoned top-ups"
            ),
            () -> assertEquals(0, AMOUNT.compareTo(balanceOf(customer))),
            () -> assertTrue(
                abandoned.stream()
                    .map(reference -> jpaRepository.findByReferenceNumber(reference).orElseThrow())
                    .allMatch(row -> row.getStatus() == TransactionStatus.PENDING && row.getLastCheckedAt() != null),
                "an abandoned top-up was concluded or never recorded as checked"
            )
        );
    }

    private TopUpService topUps(PaymentGatewayPort gateway) {
        return new TopUpService(
            paymentTransactionRepository,
            customerWalletRepository,
            gateway,
            transactionRunner,
            advisoryLock,
            new TopUpLimits(LIMIT, LIMIT),
            alarms::add
        );
    }

    private String pendingTopUp(String principalId, BigDecimal amount) {
        String reference = "TOPUP-LEDGER-" + UUID.randomUUID();
        transactionRunner.inNewTransaction(
            () -> paymentTransactionRepository.save(
                PaymentTransaction.pendingTopUp(
                    reference,
                    principalId,
                    "ledger:" + UUID.randomUUID(),
                    PaymentMethod.MIDTRANS_VA,
                    VirtualAccountBank.BCA,
                    amount
                )
                    .withGatewayResponse("midtrans-" + reference, "{\"transaction_status\":\"pending\"}")
            )
        );
        return reference;
    }

    private String pendingTopUpCreatedAt(String principalId, Instant createdAt) {
        String reference = "TOPUP-LEDGER-" + UUID.randomUUID();
        transactionRunner.inNewTransaction(
            () -> paymentTransactionRepository.save(
                new PaymentTransaction(
                    null,
                    reference,
                    "midtrans-" + reference,
                    principalId,
                    PaymentTransaction.TransactionType.TOPUP,
                    PaymentMethod.MIDTRANS_VA,
                    AMOUNT,
                    TransactionStatus.PENDING,
                    "{\"transaction_status\":\"pending\"}",
                    createdAt,
                    "ledger:" + UUID.randomUUID(),
                    VirtualAccountBank.BCA,
                    null
                )
            )
        );
        return reference;
    }

    private void seedCustomer(String principalId, BigDecimal balance) {
        transactionRunner.inNewTransaction(
            () -> customerWalletRepository.save(CustomerWallet.createInitial(principalId).topUp(balance))
        );
    }

    private BigDecimal balanceOf(String principalId) {
        return customerWalletRepository.findByCustomerPrincipalId(principalId).orElseThrow().balance();
    }

    private static TopUpCommand vaTopUp(String customer, BigDecimal amount) {
        return new TopUpCommand(
            customer,
            "ledger:" + UUID.randomUUID(),
            amount,
            PaymentMethod.MIDTRANS_VA,
            VirtualAccountBank.BCA
        );
    }

    private static GatewayCharge accepted(GatewayChargeRequest request) {
        return new GatewayCharge(
            GatewayChargeOutcome.ACCEPTED,
            "midtrans-" + request.referenceNumber(),
            new PaymentInstructions(VirtualAccountBank.BCA, "8800123", null, null),
            "{\"status_code\":\"201\",\"transaction_status\":\"pending\"}",
            null
        );
    }

    private static GatewayStatus settled(String reference, BigDecimal amount) {
        return new GatewayStatus(
            GatewaySettlement.SETTLED,
            amount,
            "midtrans-" + reference,
            "{\"transaction_status\":\"settlement\"}",
            null
        );
    }

    private static GatewayStatus stillPending(String reference) {
        return new GatewayStatus(
            GatewaySettlement.PENDING,
            null,
            "midtrans-" + reference,
            "{\"transaction_status\":\"pending\"}",
            null
        );
    }

    private static String principal() {
        return "ledger-" + UUID.randomUUID();
    }
}
