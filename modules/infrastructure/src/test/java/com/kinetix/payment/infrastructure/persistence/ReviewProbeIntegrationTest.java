package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.application.CreateEscrowHoldCommand;
import com.kinetix.payment.application.EscrowRequestFingerprint;
import com.kinetix.payment.application.EscrowService;
import com.kinetix.payment.application.WalletService;
import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.EscrowHold;
import com.kinetix.payment.domain.entity.EscrowOperation;
import com.kinetix.payment.domain.port.TransactionRunnerPort;
import com.kinetix.payment.domain.port.AdvisoryLockPort;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = PersistenceTestApplication.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_DB_URL", matches = ".+")
@TestPropertySource(properties = {
    "spring.datasource.url=${PAYMENT_TEST_DB_URL}",
    "spring.datasource.username=${PAYMENT_TEST_DB_USERNAME}",
    "spring.datasource.password=${PAYMENT_TEST_DB_PASSWORD}",
    "spring.liquibase.enabled=false",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "com.kinetix.payment.infrastructure.persistence.CapturedStatements"
})
class ReviewProbeIntegrationTest {
    private static final BigDecimal START = new BigDecimal("500000.00");
    private static final BigDecimal TOTAL = new BigDecimal("150000.00");
    private static final BigDecimal MERCHANT_AMOUNT = new BigDecimal("130000.00");
    private static final BigDecimal SHIPPING = new BigDecimal("20000.00");

    @Autowired private TransactionRunnerPort transactionRunner;
    @Autowired private AdvisoryLockPort advisoryLock;
    @Autowired private EscrowAdapter escrowRepository;
    @Autowired private CustomerWalletAdapter customerWalletRepository;
    @Autowired private MerchantWalletAdapter merchantWalletRepository;
    @Autowired private DriverWalletAdapter driverWalletRepository;
    @Autowired private PaymentTransactionAdapter paymentTransactionRepository;
    @Autowired private EscrowIdempotencyAdapter idempotencyRepository;

    private EscrowService escrow() {
        return new EscrowService(escrowRepository, customerWalletRepository, merchantWalletRepository,
            driverWalletRepository, paymentTransactionRepository, idempotencyRepository,
            transactionRunner, advisoryLock
        );
    }

    private WalletService wallets() {
        return new WalletService(customerWalletRepository, merchantWalletRepository,
            driverWalletRepository, transactionRunner, advisoryLock
        );
    }

    @Test
    void aRefundRacingItsOwnCreateNeverLeavesADebitWithNoHoldAndNoRefund() throws Exception {
        List<String> violations = race(advisoryLock, 120, "with the advisory lock");
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    @Test
    void withoutTheAdvisoryLockTheSameRaceStrandsMoney() throws Exception {
        AdvisoryLockPort noop =
            new AdvisoryLockPort() {
                @Override public void lockOrder(String orderNumber) {}
                @Override public void lockWalletOwner(String principalId) {}
            };
        List<String> violations = race(noop, 120, "WITHOUT the advisory lock");
        System.out.println("### control: " + violations.size() + " violations without the lock");
        violations.stream().distinct().limit(5).forEach(v -> System.out.println("###   " + v));
        assertTrue(!violations.isEmpty(),
            "the control found no violation either, so this probe cannot detect the defect"
        );
    }

    private List<String> race(
        AdvisoryLockPort lock, int rounds, String label
    ) throws Exception {
        EscrowService escrow = new EscrowService(
            escrowRepository, customerWalletRepository,
            merchantWalletRepository, driverWalletRepository, paymentTransactionRepository,
            idempotencyRepository, transactionRunner, lock
        );
        List<String> violations = new CopyOnWriteArrayList<>();
        List<String> shapes = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(4);

        try {
            for (int i = 0; i < rounds; i++) {
                String order = "PROBE-" + UUID.randomUUID();
                String customer = principal();
                String merchant = principal();
                seedCustomer(customer, START);

                CyclicBarrier gate = new CyclicBarrier(2);
                Future<String> creating = pool.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);
                    try {
                        escrow.createEscrowHold(new CreateEscrowHoldCommand(order, customer, merchant,
                            null, TOTAL, MERCHANT_AMOUNT, SHIPPING, "k-create-" + order,
                            EscrowRequestFingerprint.forCreateHold(
                                order, customer, merchant, null,
                                TOTAL, MERCHANT_AMOUNT, SHIPPING
                            )
                        ));
                        return "create:ok";
                    } catch (RuntimeException failure) {
                        return "create:" + failure.getClass().getSimpleName();
                    }
                });
                Future<String> refunding = pool.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);
                    try {
                        escrow.refundEscrow(order, "compensation", "k-refund-" + order);
                        return "refund:ok";
                    } catch (RuntimeException failure) {
                        return "refund:" + failure.getClass().getSimpleName();
                    }
                });
                String outcome = creating.get(30, TimeUnit.SECONDS) + " / "
                    + refunding.get(30, TimeUnit.SECONDS);

                BigDecimal balance = balanceOf(customer);
                Optional<EscrowHold> hold = escrowRepository.findByOrderNumber(order);
                boolean refundRecorded = idempotencyRepository.existsFor(EscrowOperation.REFUND, order);
                shapes.add(outcome + " | hold=" + hold.map(h -> h.status().name()).orElse("none")
                    + " | refundRow=" + refundRecorded + " | balance=" + balance.toPlainString()
                );

                if (hold.isEmpty() && balance.compareTo(START) != 0) {
                    violations.add("STRANDED DEBIT: no hold row but balance is " + balance
                        + " on order " + order
                    );
                }
                if (hold.isPresent() && hold.get().status() == EscrowHold.EscrowStatus.HELD
                    && refundRecorded) {
                    violations.add("HELD AND REFUNDED: order " + order
                        + " has a live hold and a committed REFUND record"
                    );
                }
                if (hold.isPresent() && hold.get().status() == EscrowHold.EscrowStatus.REFUNDED
                    && balance.compareTo(START) != 0) {
                    violations.add("REFUNDED BUT NOT CREDITED: order " + order + " balance " + balance);
                }
                if (hold.isPresent() && hold.get().status() == EscrowHold.EscrowStatus.HELD
                    && balance.compareTo(START.subtract(TOTAL)) != 0) {
                    violations.add("HELD BUT BALANCE WRONG: order " + order + " balance " + balance);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        System.out.println("### create/refund race " + label + ", " + rounds + " rounds:");
        shapes.stream().distinct().forEach(s -> System.out.println("###   " + s));
        return violations;
    }

    @Test
    void concurrentTopUpsNeverEraseAnEscrowDebit() throws Exception {
        EscrowService escrow = escrow();
        WalletService wallets = wallets();
        String customer = principal();
        String merchant = principal();
        String order = "PROBE-" + UUID.randomUUID();
        seedCustomer(customer, START);

        int topUps = 20;
        BigDecimal each = new BigDecimal("1000.00");
        CyclicBarrier gate = new CyclicBarrier(topUps + 1);
        ExecutorService pool = Executors.newFixedThreadPool(topUps + 1);
        List<Future<?>> running = new ArrayList<>();
        try {
            for (int i = 0; i < topUps; i++) {
                running.add(pool.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);
                    return wallets.topUpCustomerWallet(customer, each);
                }));
            }
            Future<?> debit = pool.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return escrow.createEscrowHold(new CreateEscrowHoldCommand(order, customer, merchant,
                    null, TOTAL, MERCHANT_AMOUNT, SHIPPING, "k-" + order,
                    EscrowRequestFingerprint.forCreateHold(order, customer, merchant, null,
                        TOTAL, MERCHANT_AMOUNT, SHIPPING)
                ));
            });
            for (Future<?> f : running) {
                f.get(30, TimeUnit.SECONDS);
            }
            debit.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        BigDecimal expected = START.add(each.multiply(new BigDecimal(topUps))).subtract(TOTAL);
        BigDecimal actual = balanceOf(customer);
        System.out.println("### top-up vs debit: expected " + expected.toPlainString()
            + " actual " + actual.toPlainString()
        );
        assertEquals(0, expected.compareTo(actual),
            "expected " + expected + " but the wallet holds " + actual
                + "; a lost update ate " + expected.subtract(actual)
        );
    }

    @Test
    void mixedTrafficOverSharedWalletsNeverDeadlocks() throws Exception {
        EscrowService escrow = escrow();
        WalletService wallets = wallets();
        String[] customers = {principal(), principal(), principal()};
        String[] merchants = {principal(), principal()};
        String[] drivers = {principal(), principal()};
        for (String c : customers) {
            seedCustomer(c, new BigDecimal("100000000.00"));
        }

        int workers = 16;
        int perWorker = 30;
        CyclicBarrier gate = new CyclicBarrier(workers);
        List<String> deadlocks = new CopyOnWriteArrayList<>();
        List<String> other = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<?>> running = new ArrayList<>();

        try {
            for (int w = 0; w < workers; w++) {
                int me = w;
                running.add(pool.submit(() -> {
                    gate.await(15, TimeUnit.SECONDS);
                    for (int n = 0; n < perWorker; n++) {
                        String order = "PROBE-" + UUID.randomUUID();
                        String c = customers[(me + n) % customers.length];
                        String m = merchants[(me + n) % merchants.length];
                        String d = drivers[(me * 3 + n) % drivers.length];
                        try {
                            switch (n % 4) {
                                case 0 -> {
                                    escrow.createEscrowHold(new CreateEscrowHoldCommand(order, c, m, d,
                                        TOTAL, MERCHANT_AMOUNT, SHIPPING, "k-" + order,
                                        EscrowRequestFingerprint.forCreateHold(order, c, m, d,
                                            TOTAL, MERCHANT_AMOUNT, SHIPPING
                                        )
                                    ));
                                    escrow.releaseEscrow(order, "r-" + order);
                                }
                                case 1 -> {
                                    escrow.createEscrowHold(new CreateEscrowHoldCommand(order, c, m, d,
                                        TOTAL, MERCHANT_AMOUNT, SHIPPING, "k-" + order,
                                        EscrowRequestFingerprint.forCreateHold(order, c, m, d,
                                            TOTAL, MERCHANT_AMOUNT, SHIPPING
                                        )
                                    ));
                                    escrow.refundEscrow(order, "cancel", "f-" + order);
                                }
                                case 2 -> wallets.topUpCustomerWallet(c, new BigDecimal("500.00"));
                                default -> {
                                    wallets.getMerchantWallet(m);
                                    wallets.getDriverWallet(d);
                                }
                            }
                        } catch (RuntimeException failure) {
                            String text = describe(failure);
                            if (text.contains("40P01") || text.toLowerCase().contains("deadlock")) {
                                deadlocks.add(text);
                            } else {
                                other.add(failure.getClass().getSimpleName() + " [" + constraintOf(failure) + "]");
                            }
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : running) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.println("### mixed traffic: deadlocks=" + deadlocks.size()
            + " otherFailures=" + other.size() + " " + other.stream().distinct().toList()
        );
        assertTrue(deadlocks.isEmpty(), String.join("\n", deadlocks));
    }

    @Test
    void aFirstEverMerchantWalletSurvivesACheckoutMeetingABalanceRead() throws Exception {
        EscrowService escrow = escrow();
        WalletService wallets = wallets();
        List<String> failures = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        int rounds = 150;
        int readers = 4;

        try {
            for (int i = 0; i < rounds; i++) {
                String merchant = principal();
                String customer = principal();
                String order = "PROBE-" + UUID.randomUUID();
                seedCustomer(customer, START);
                CyclicBarrier gate = new CyclicBarrier(1 + readers);

                Future<String> checkout = pool.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);

                    try {
                        escrow.createEscrowHold(new CreateEscrowHoldCommand(order, customer, merchant,
                            null, TOTAL, MERCHANT_AMOUNT, SHIPPING, "k-" + order,
                            EscrowRequestFingerprint.forCreateHold(order, customer, merchant, null,
                                TOTAL, MERCHANT_AMOUNT, SHIPPING
                            )
                        ));
                        return "checkout:ok";
                    } catch (RuntimeException failure) {
                        return "checkout:" + failure.getClass().getSimpleName();
                    }
                });
                List<Future<String>> reading = new ArrayList<>();
                for (int r = 0; r < readers; r++) {
                    reading.add(pool.submit(() -> {
                        gate.await(10, TimeUnit.SECONDS);
                        try {
                            wallets.getMerchantWallet(merchant);
                            return "read:ok";
                        } catch (RuntimeException failure) {
                            return "read:" + failure.getClass().getSimpleName()
                                + " [" + constraintOf(failure) + "]";
                        }
                    }));
                }
                StringBuilder outcome = new StringBuilder(checkout.get(30, TimeUnit.SECONDS));
                for (Future<String> f : reading) {
                    outcome.append(" / ").append(f.get(30, TimeUnit.SECONDS));
                }
                if (outcome.toString().contains("Exception")) {
                    failures.add(outcome.toString());
                }
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.println("### first-ever merchant wallet race over " + rounds + " rounds: "
            + failures.size() + " non-clean outcomes " + failures.stream().distinct().toList()
        );
        assertTrue(failures.isEmpty(), String.join(", ", failures));
    }

    @Test
    void theOldUnlockedTopUpLosesTheDebit() throws Exception {
        EscrowService escrow = escrow();
        String customer = principal();
        String merchant = principal();
        String order = "PROBE-" + UUID.randomUUID();
        seedCustomer(customer, START);

        int topUps = 20;
        BigDecimal each = new BigDecimal("1000.00");
        CyclicBarrier gate = new CyclicBarrier(topUps + 1);
        ExecutorService pool = Executors.newFixedThreadPool(topUps + 1);
        List<Future<?>> running = new ArrayList<>();

        try {
            for (int i = 0; i < topUps; i++) {
                running.add(pool.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);
                    CustomerWallet stale = customerWalletRepository
                        .findByCustomerPrincipalId(customer).orElseThrow();
                    Thread.sleep(30);
                    return customerWalletRepository.save(stale.topUp(each));
                }));
            }
            Future<?> debit = pool.submit(() -> {
                gate.await(10, TimeUnit.SECONDS);
                return escrow.createEscrowHold(new CreateEscrowHoldCommand(order, customer, merchant,
                    null, TOTAL, MERCHANT_AMOUNT, SHIPPING, "k-" + order,
                    EscrowRequestFingerprint.forCreateHold(order, customer, merchant, null,
                        TOTAL, MERCHANT_AMOUNT, SHIPPING
                    )
                ));
            });
            for (Future<?> f : running) {
                f.get(30, TimeUnit.SECONDS);
            }
            debit.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        BigDecimal expected = START.add(each.multiply(new BigDecimal(topUps))).subtract(TOTAL);
        BigDecimal actual = balanceOf(customer);
        System.out.println("### CONTROL old unlocked top-up: expected " + expected.toPlainString()
            + " actual " + actual.toPlainString() + " (lost " + expected.subtract(actual).toPlainString() + ")"
        );
        assertTrue(expected.compareTo(actual) != 0,
            "the control did not lose an update, so attack 2 proves nothing"
        );
    }

    private static String constraintOf(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.postgresql.util.PSQLException pg
                && pg.getServerErrorMessage() != null) {
                return pg.getServerErrorMessage().getConstraint() + "/" + pg.getSQLState();
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return "no-constraint";
    }

    private void seedCustomer(String principalId, BigDecimal balance) {
        transactionRunner.inNewTransaction(() -> customerWalletRepository.save(
            CustomerWallet.createInitial(principalId).topUp(balance)
        ));
    }

    private BigDecimal balanceOf(String principalId) {
        return customerWalletRepository.findByCustomerPrincipalId(principalId)
            .map(CustomerWallet::balance)
            .orElse(BigDecimal.ZERO);
    }

    private static String describe(Throwable error) {
        StringBuilder chain = new StringBuilder();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            chain.append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
            if (cause.getCause() == cause) {
                break;
            }
            chain.append(" <- ");
        }
        return chain.toString();
    }

    private static String principal() {
        return UUID.randomUUID().toString();
    }
}
