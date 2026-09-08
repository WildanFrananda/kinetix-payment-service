package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.port.AdvisoryLockPort;
import com.kinetix.payment.domain.port.TransactionRunnerPort;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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
class AdvisoryLockIntegrationTest {
    private static final int ORDER_NUMBER_NAMESPACE = 1;
    private static final String HOLD_THE_LOCK = "SELECT pg_advisory_xact_lock(?, hashtext(?))";

    private static final long HOLD_MILLIS = 1200;

    @Autowired private TransactionRunnerPort transactionRunner;
    @Autowired private AdvisoryLockPort advisoryLock;
    @Autowired private DataSource dataSource;

    @Test
    void aLockOnOneOrderNumberSerialisesTwoTransactionsThatShareNoRow() throws Exception {
        String order = order();
        warmUp();

        long waited = timeALockAttemptWhile(order, order);

        assertTrue(waited >= HOLD_MILLIS - 400,
            "the second transaction acquired the order lock after " + waited + "ms while another "
                + "transaction held it for " + HOLD_MILLIS + "ms; it was not serialised"
        );
    }

    @Test
    void aLockOnADifferentOrderNumberDoesNotWaitAtAll() throws Exception {
        warmUp();

        long waited = timeALockAttemptWhile(order(), order());

        assertTrue(waited < HOLD_MILLIS / 2,
            "locking a different order number waited " + waited + "ms behind an unrelated holder"
        );
    }

    void aBlockedOrderLockGivesUpAtTheLockTimeoutInsteadOfHanging() throws Exception {
        String order = order();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = holdTheOrderLock(order, held, release);

        try {
            assertTrue(held.await(5, TimeUnit.SECONDS), "the holder never took the lock");

            long startedAt = System.nanoTime();
            RuntimeException failure = assertThrows(RuntimeException.class, () ->
                transactionRunner.inNewTransaction(() -> {
                    advisoryLock.lockOrder(order);
                    return null;
                })
            );
            long waited = millisSince(startedAt);

            assertTrue(mentionsLockTimeout(failure),
                "expected the wait to end at lock_timeout, got: " + describe(failure));
            assertTrue(waited < 4_500,
                "the blocked lock waited " + waited + "ms, which is past the 3s lock_timeout and "
                    + "the 4s transaction budget it is supposed to sit inside"
            );
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test
    void anOrderLockIsReleasedByTheCommitOfTheTransactionThatTookIt() {
        String order = order();
        warmUp();

        transactionRunner.inNewTransaction(() -> {
            advisoryLock.lockOrder(order);
            return null;
        });

        long startedAt = System.nanoTime();
        transactionRunner.inNewTransaction(() -> {
            advisoryLock.lockOrder(order);
            return null;
        });
        long waited = millisSince(startedAt);

        assertTrue(waited < 500,
            "re-taking the same order lock after a commit waited " + waited + "ms, so the previous "
                + "transaction never released it"
        );
    }

    @Test
    void anOrderLockTakenOutsideATransactionIsRefused() {
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
            () -> advisoryLock.lockOrder(order())
        );

        assertTrue(refusal.getMessage().contains("transaction"), refusal.getMessage());
    }

    @Test
    void aWalletOwnerLockOutsideATransactionIsRefusedToo() {
        assertThrows(IllegalStateException.class,
            () -> advisoryLock.lockWalletOwner(UUID.randomUUID().toString())
        );
    }

    private long timeALockAttemptWhile(String heldOrder, String lockedOrder) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = holdTheOrderLock(heldOrder, held, release);

        try {
            assertTrue(held.await(5, TimeUnit.SECONDS), "the holder never took the lock");

            AtomicLong waited = new AtomicLong(-1);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread waiter = new Thread(() -> {
                long startedAt = System.nanoTime();
                try {
                    transactionRunner.inNewTransaction(() -> {
                        advisoryLock.lockOrder(lockedOrder);
                        return null;
                    });
                    waited.set(millisSince(startedAt));
                } catch (Throwable error) {
                    failed.set(error);
                }
            });
            waiter.start();

            Thread.sleep(HOLD_MILLIS);
            release.countDown();
            waiter.join(10_000);

            if (failed.get() != null) {
                throw new AssertionError("the waiting transaction failed", failed.get());
            }
            assertTrue(waited.get() >= 0, "the waiting transaction never finished");
            return waited.get();
        } finally {
            release.countDown();
            holder.join();
        }
    }

    private Thread holdTheOrderLock(String orderNumber, CountDownLatch held, CountDownLatch release) {
        Thread holder = new Thread(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(HOLD_THE_LOCK)) {
                    statement.setInt(1, ORDER_NUMBER_NAMESPACE);
                    statement.setString(2, orderNumber);
                    statement.execute();
                }
                held.countDown();
                release.await(30, TimeUnit.SECONDS);
                connection.rollback();
            } catch (Exception error) {
                held.countDown();
                throw new IllegalStateException("the holder could not take the lock", error);
            }
        });
        holder.setDaemon(true);
        holder.start();
        return holder;
    }

    private void warmUp() {
        assertNotNull(transactionRunner.inNewTransaction(() -> {
            advisoryLock.lockOrder(order());
            return Boolean.TRUE;
        }));
    }

    private static boolean mentionsLockTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("lock timeout")) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
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

    private static long millisSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private static String order() {
        return "TEST-" + UUID.randomUUID();
    }
}
