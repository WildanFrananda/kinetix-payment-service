package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.DriverWallet;
import com.kinetix.payment.domain.entity.EscrowHold;
import com.kinetix.payment.domain.entity.EscrowIdempotencyRecord;
import com.kinetix.payment.domain.entity.MerchantWallet;
import com.kinetix.payment.domain.entity.EscrowOperation;
import com.kinetix.payment.domain.entity.IdempotencyKeySource;
import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.exception.DomainException;
import com.kinetix.payment.domain.exception.DuplicateIdempotencyKeyException;
import com.kinetix.payment.domain.exception.DuplicateOrderNumberException;
import com.kinetix.payment.domain.port.TransactionRunnerPort;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
class EscrowPersistenceIntegrationTest {
    private static final String CUSTOMER = "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7";
    private static final String MERCHANT = "3c9a77b1-58de-4a01-8f2e-6d4b19c0a8f3";
    private static final BigDecimal TOTAL = new BigDecimal("150000.00");
    private static final BigDecimal MERCHANT_AMOUNT = new BigDecimal("130000.00");
    private static final BigDecimal SHIPPING = new BigDecimal("20000.00");

    @Autowired private TransactionRunnerPort transactionRunner;
    @Autowired private EscrowIdempotencyAdapter idempotencyRepository;
    @Autowired private EscrowAdapter escrowRepository;
    @Autowired private PaymentTransactionAdapter paymentTransactionRepository;
    @Autowired private CustomerWalletAdapter customerWalletRepository;
    @Autowired private MerchantWalletAdapter merchantWalletRepository;
    @Autowired private DriverWalletAdapter driverWalletRepository;
    @Autowired private DataSource dataSource;

    @Test
    void aRepeatedIdempotencyKeyIsTranslatedIntoTheReplayableFailure() {
        String key = "test:" + UUID.randomUUID();
        String order = order();

        transactionRunner.inNewTransaction(() -> idempotencyRepository.save(record(order, key)));

        assertThrows(DuplicateIdempotencyKeyException.class, () ->
            transactionRunner.inNewTransaction(() -> idempotencyRepository.save(record(order, key)))
        );
    }

    @Test
    void oneKeyIsFreeToRepeatAcrossTheThreeOperations() {
        String key = "test:" + UUID.randomUUID();
        String order = order();

        transactionRunner.inNewTransaction(() -> idempotencyRepository.save(
            record(order, key, EscrowOperation.CREATE_HOLD))
        );
        transactionRunner.inNewTransaction(() -> idempotencyRepository.save(
            record(order, key, EscrowOperation.RELEASE))
        );
        transactionRunner.inNewTransaction(() -> idempotencyRepository.save(
            record(order, key, EscrowOperation.REFUND))
        );

        assertTrue(idempotencyRepository.existsFor(EscrowOperation.REFUND, order));
    }

    @Test
    void aSecondHoldForOneOrderIsTranslatedIntoTheDifferentKeyFailure() {
        String order = order();

        transactionRunner.inNewTransaction(() -> escrowRepository.save(hold(order)));

        assertThrows(DuplicateOrderNumberException.class, () ->
            transactionRunner.inNewTransaction(() -> escrowRepository.save(hold(order)))
        );
    }

    @Test
    void aRepeatedLedgerReferenceIsClassifiedRatherThanLeftRaw() {
        String order = order();

        transactionRunner.inNewTransaction(() -> paymentTransactionRepository.save(ledgerRow(order)));

        assertThrows(DomainException.class, () ->
            transactionRunner.inNewTransaction(() -> paymentTransactionRepository.save(ledgerRow(order)))
        );
    }

    @Test
    void everythingWrittenBeforeAFailureRollsBackTogether() {
        String order = order();
        String key = "test:" + UUID.randomUUID();

        assertThrows(IllegalStateException.class, () -> transactionRunner.inNewTransaction(() -> {
            idempotencyRepository.save(record(order, key));
            escrowRepository.save(hold(order));
            throw new IllegalStateException("the work failed after both writes");
        }));

        assertTrue(idempotencyRepository.find(EscrowOperation.CREATE_HOLD, key).isEmpty());
        assertTrue(escrowRepository.findByOrderNumber(order).isEmpty());
    }

    @Test
    void aTransactionThatReturnsNormallyCommits() {
        String order = order();
        String key = "test:" + UUID.randomUUID();

        transactionRunner.inNewTransaction(() -> {
            idempotencyRepository.save(record(order, key));
            return escrowRepository.save(hold(order));
        });

        assertTrue(idempotencyRepository.find(EscrowOperation.CREATE_HOLD, key).isPresent());
        assertTrue(escrowRepository.findByOrderNumber(order).isPresent());
    }

    @Test
    void theLockingFinderReadsTheRowItLocksAndSaysWhichLockItTook() {
        String order = order();
        EscrowHold saved = transactionRunner.inNewTransaction(() -> escrowRepository.save(hold(order)));

        CapturedStatements.forget();
        EscrowHold locked = transactionRunner.inNewTransaction(() ->
            escrowRepository.findByOrderNumberForUpdate(order).orElseThrow()
        );

        assertEquals(saved.id(), locked.id());
        assertEquals(0, TOTAL.compareTo(locked.totalOrderAmount()));
        assertTrue(CapturedStatements.sawSelectOn("escrow_holds", "for no key update"),
            "no locking select on escrow_holds was emitted; statements were "
                + CapturedStatements.selectsOn("escrow_holds")
        );
    }

    @Test
    void theWalletLockingFindersReadTheRowsTheyLockAndSayWhichLockTheyTook() {
        String customer = principal();
        String merchant = principal();
        String driver = principal();
        transactionRunner.inNewTransaction(() -> {
            customerWalletRepository.save(CustomerWallet.createInitial(customer));
            merchantWalletRepository.save(MerchantWallet.createInitial(merchant));
            return driverWalletRepository.save(DriverWallet.createInitial(driver));
        });

        CapturedStatements.forget();
        transactionRunner.inNewTransaction(() -> {
            assertTrue(customerWalletRepository.findByCustomerPrincipalIdForUpdate(customer).isPresent());
            assertTrue(merchantWalletRepository.findByMerchantPrincipalIdForUpdate(merchant).isPresent());
            assertTrue(driverWalletRepository.findByDriverPrincipalIdForUpdate(driver).isPresent());
            return null;
        });

        assertTrue(CapturedStatements.sawSelectOn("customer_wallets", "for no key update"),
            "customer_wallets was read without a row lock: " + CapturedStatements.selectsOn("customer_wallets")
        );
        assertTrue(CapturedStatements.sawSelectOn("merchant_wallets", "for no key update"),
            "merchant_wallets was read without a row lock: " + CapturedStatements.selectsOn("merchant_wallets")
        );
        assertTrue(CapturedStatements.sawSelectOn("driver_wallets", "for no key update"),
            "driver_wallets was read without a row lock: " + CapturedStatements.selectsOn("driver_wallets")
        );
    }

    @Test
    void twoWritersCannotHoldOneCustomerWalletRowAtTheSameTime() throws Exception {
        String customer = principal();
        transactionRunner.inNewTransaction(() ->
            customerWalletRepository.save(CustomerWallet.createInitial(customer))
        );

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM customer_wallets WHERE customer_principal_id = ? "
                        + "FOR NO KEY UPDATE")) {
                    statement.setString(1, customer);
                    statement.execute();
                }
                held.countDown();
                release.await(30, TimeUnit.SECONDS);
                connection.rollback();
            } catch (Exception error) {
                held.countDown();
                throw new IllegalStateException("the holder could not take the row", error);
            }
        });
        holder.setDaemon(true);
        holder.start();

        try {
            assertTrue(held.await(5, TimeUnit.SECONDS), "the holder never took the row");
            AtomicLong waited = new AtomicLong(-1);
            Thread waiter = new Thread(() -> {
                long startedAt = System.nanoTime();
                transactionRunner.inNewTransaction(() ->
                    customerWalletRepository.findByCustomerPrincipalIdForUpdate(customer)
                );
                waited.set((System.nanoTime() - startedAt) / 1_000_000);
            });
            waiter.start();

            Thread.sleep(1_000);
            release.countDown();
            waiter.join(10_000);

            assertTrue(waited.get() >= 600,
                "the second writer read the wallet row after " + waited.get() + "ms while another "
                    + "transaction held it for 1000ms; the lock excluded nobody"
            );
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test
    void aTombstoneIsARefundRecordWithNoHoldBehindIt() {
        String order = order();
        String key = "test:" + UUID.randomUUID();

        EscrowIdempotencyRecord tombstone = transactionRunner.inNewTransaction(() ->
            idempotencyRepository.save(record(order, key, EscrowOperation.REFUND))
        );

        assertTrue(tombstone.isTombstone());
        assertFalse(record(order, key, EscrowOperation.CREATE_HOLD).isTombstone());
    }

    private static String order() {
        return "TEST-" + UUID.randomUUID();
    }

    private static String principal() {
        return UUID.randomUUID().toString();
    }

    private static EscrowIdempotencyRecord record(String orderNumber, String key) {
        return record(orderNumber, key, EscrowOperation.CREATE_HOLD);
    }

    private static EscrowIdempotencyRecord record(
        String orderNumber, String key, EscrowOperation operation
    ) {
        return EscrowIdempotencyRecord.opening(
            operation, key, IdempotencyKeySource.CALLER, orderNumber,
            "0".repeat(64), null
        );
    }

    private static EscrowHold hold(String orderNumber) {
        return EscrowHold.createNewHold(
            orderNumber, CUSTOMER, MERCHANT, null, TOTAL, MERCHANT_AMOUNT, SHIPPING
        );
    }

    private static PaymentTransaction ledgerRow(String orderNumber) {
        return new PaymentTransaction(
            null,
            "escrow:hold:" + orderNumber,
            null,
            CUSTOMER,
            PaymentTransaction.TransactionType.CHECKOUT_PAYMENT,
            PaymentTransaction.PaymentMethod.INTERNAL_WALLET,
            TOTAL,
            PaymentTransaction.TransactionStatus.SUCCESS,
            null,
            Instant.now()
        );
    }
}
