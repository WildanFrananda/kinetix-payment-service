package com.kinetix.payment.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kinetix.payment.application.CreateEscrowHoldCommand;
import com.kinetix.payment.application.EscrowRequestFingerprint;
import com.kinetix.payment.application.EscrowService;
import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.LiabilityAccount;
import com.kinetix.payment.domain.entity.LiabilityKind;
import com.kinetix.payment.domain.port.AdvisoryLockPort;
import com.kinetix.payment.domain.port.TransactionRunnerPort;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
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
class LiabilitySnapshotIntegrationTest {
    private static final BigDecimal TOTAL = new BigDecimal("150000.00");
    private static final BigDecimal MERCHANT_AMOUNT = new BigDecimal("130000.00");
    private static final BigDecimal SHIPPING = new BigDecimal("20000.00");
    private static final BigDecimal FUNDED = new BigDecimal("1500000000.00");

    @Autowired private PostgresLiabilitySnapshot snapshots;
    @Autowired private TransactionRunnerPort transactionRunner;
    @Autowired private AdvisoryLockPort advisoryLock;
    @Autowired private EscrowAdapter escrowRepository;
    @Autowired private CustomerWalletAdapter customerWalletRepository;
    @Autowired private MerchantWalletAdapter merchantWalletRepository;
    @Autowired private DriverWalletAdapter driverWalletRepository;
    @Autowired private PaymentTransactionAdapter paymentTransactionRepository;
    @Autowired private SuspenseWalletAdapter suspenseWalletRepository;
    @Autowired private EscrowIdempotencyAdapter idempotencyRepository;

    @Test
    void everyWalletAppearsOnceWithEachOfItsBalances() {
        String customer = UUID.randomUUID().toString();
        String merchant = UUID.randomUUID().toString();
        String driver = UUID.randomUUID().toString();
        fund(customer, new BigDecimal("500000.00"));
        hold(escrow(), "ORD-LIAB-" + UUID.randomUUID(), customer, merchant, driver);
        Instant before = Instant.now();

        SnapshotCapture snapshot = take("IDR");

        assertTrue(!snapshot.asOf.isBefore(before.minusSeconds(5)), "dated " + snapshot.asOf);
        assertEquals(Map.of(LiabilityKind.CUSTOMER_BALANCE, new BigDecimal("350000.00")), owed(snapshot, customer));
        assertEquals(
            Map.of(
                LiabilityKind.MERCHANT_AVAILABLE,
                new BigDecimal("0.00"),
                LiabilityKind.MERCHANT_PENDING_ESCROW,
                MERCHANT_AMOUNT
            ),
            owed(snapshot, merchant)
        );
        assertEquals(
            Map.of(
                LiabilityKind.DRIVER_AVAILABLE,
                new BigDecimal("0.00"),
                LiabilityKind.DRIVER_PENDING_ESCROW,
                SHIPPING
            ),
            owed(snapshot, driver)
        );
        assertEquals(
            snapshot.accounts.size(),
            snapshot.accounts.stream()
                .map(account -> account.kind() + "/" + account.holder())
                .distinct()
                .count()
        );
    }

    @Test
    void aWalletInAnotherCurrencyBelongsToThatCurrencysSnapshot() {
        String customer = UUID.randomUUID().toString();
        transactionRunner.inNewTransaction(
            () -> customerWalletRepository.save(
                new CustomerWallet(
                    null,
                    customer,
                    new BigDecimal("12.34"),
                    BigDecimal.ZERO,
                    "USD",
                    Instant.now(),
                    Instant.now()
                )
            )
        );

        assertTrue(take("IDR").of(customer).isEmpty());
        assertEquals(
            Map.of(LiabilityKind.CUSTOMER_BALANCE, new BigDecimal("12.34")),
            owed(take("USD"), customer)
        );
    }

    @Test
    void escrowTrafficNeverChangesWhatASnapshotSeesOwed() throws Exception {
        String customer = UUID.randomUUID().toString();
        String merchant = UUID.randomUUID().toString();
        String driver = UUID.randomUUID().toString();
        Set<String> parties = Set.of(customer, merchant, driver);
        fund(customer, FUNDED);
        EscrowService escrow = escrow();
        AtomicBoolean trading = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<BigDecimal> seen = new ArrayList<>();
        int moves;
        try {
            Future<Integer> traffic = pool.submit(() -> {
                int done = 0;
                long until = System.nanoTime() + Duration.ofSeconds(6).toNanos();
                while (System.nanoTime() < until && done < 400) {
                    String order = "ORD-LIAB-" + UUID.randomUUID();
                    hold(escrow, order, customer, merchant, driver);
                    if (done % 2 == 0) {
                        escrow.releaseEscrow(order);
                    } else {
                        escrow.refundEscrow(order, "conservation probe", "r-" + order);
                    }

                    done++;
                }

                trading.set(false);
                return done;
            });

            Future<?> reader = pool.submit(() -> {
                while (trading.get()) {
                    BigDecimal owed = take("IDR").owedTo(parties);
                    synchronized (seen) {
                        seen.add(owed);
                    }
                }
            });

            moves = traffic.get();
            reader.get();
        } finally {
            pool.shutdownNow();
        }

        List<BigDecimal> drifted = seen.stream().filter(owed -> owed.compareTo(FUNDED) != 0).toList();
        assertTrue(moves >= 20, "only " + moves + " escrow movements ran");
        assertTrue(seen.size() >= 20, "only " + seen.size() + " snapshots were taken");
        assertTrue(
            drifted.isEmpty(),
            drifted.size() + " of " + seen.size() + " snapshots over " + moves
                + " escrow movements saw money that was in flight, first " + drifted.stream().limit(5).toList()
        );
        assertEquals(0, take("IDR").owedTo(parties).compareTo(FUNDED));
    }

    private SnapshotCapture take(String currency) {
        SnapshotCapture capture = new SnapshotCapture();
        snapshots.read(currency, capture);
        return capture;
    }

    private static Map<LiabilityKind, BigDecimal> owed(SnapshotCapture snapshot, String holder) {
        return snapshot.of(holder).stream()
            .collect(Collectors.toMap(LiabilityAccount::kind, LiabilityAccount::balance));
    }

    private void fund(String customer, BigDecimal amount) {
        transactionRunner.inNewTransaction(
            () -> customerWalletRepository.save(
                CustomerWallet.createInitial(customer).topUp(amount)
            )
        );
    }

    private void hold(EscrowService escrow, String order, String customer, String merchant, String driver) {
        escrow.createEscrowHold(
            new CreateEscrowHoldCommand(
                order,
                customer,
                merchant,
                driver,
                TOTAL,
                MERCHANT_AMOUNT,
                SHIPPING,
                "k-" + order,
                EscrowRequestFingerprint.forCreateHold(
                    order,
                    customer,
                    merchant,
                    driver,
                    TOTAL,
                    MERCHANT_AMOUNT,
                    SHIPPING
                )
            )
        );
    }

    private EscrowService escrow() {
        return new EscrowService(
            escrowRepository,
            customerWalletRepository,
            merchantWalletRepository,
            driverWalletRepository,
            suspenseWalletRepository,
            paymentTransactionRepository,
            idempotencyRepository,
            transactionRunner,
            advisoryLock
        );
    }
}
