package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.application.CreateEscrowHoldCommand;
import com.kinetix.payment.application.EscrowOutcome;
import com.kinetix.payment.application.EscrowRequestFingerprint;
import com.kinetix.payment.application.EscrowService;
import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.DriverWallet;
import com.kinetix.payment.domain.entity.EscrowHold;
import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.entity.SuspenseWallet;
import com.kinetix.payment.domain.exception.DomainException;
import com.kinetix.payment.domain.port.AdvisoryLockPort;
import com.kinetix.payment.domain.port.TransactionRunnerPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
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
    "spring.jpa.hibernate.ddl-auto=validate"
})
class ShippingSettlementIntegrationTest {
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
    @Autowired private SuspenseWalletAdapter suspenseWalletRepository;
    @Autowired private EscrowIdempotencyAdapter idempotencyRepository;

    @Test
    void aCourierWhoDeliversAfterADriverlessReleaseIsStillPaid() {
        EscrowService escrow = escrow();
        String order = "ORD-SETTLE-" + UUID.randomUUID();
        String driver = UUID.randomUUID().toString();
        holdWithoutADriver(escrow, order);
        escrow.releaseEscrow(order);
        BigDecimal suspenseBefore = suspenseAvailable();

        EscrowOutcome outcome = escrow.settleShippingFee(order, driver);

        assertFalse(outcome.alreadyApplied());
        assertEquals(driver, outcome.hold().driverPrincipalId());
        assertEquals(0, driverAvailable(driver).compareTo(SHIPPING));
        assertEquals(0, suspenseBefore.subtract(suspenseAvailable()).compareTo(SHIPPING));
        assertEquals(driver, ledger("escrow:shipping:" + order + ":paid").principalId());
        assertEquals(SuspenseWallet.UNASSIGNED_DRIVER_SHIPPING_FEE,
            ledger("escrow:shipping:" + order).principalId()
        );
    }

    @Test
    void settlingAgainAfterADriverlessReleaseMovesNoMoreMoney() {
        EscrowService escrow = escrow();
        String order = "ORD-SETTLE-" + UUID.randomUUID();
        String driver = UUID.randomUUID().toString();
        holdWithoutADriver(escrow, order);
        escrow.releaseEscrow(order);
        escrow.settleShippingFee(order, driver);
        BigDecimal suspenseBefore = suspenseAvailable();

        EscrowOutcome again = escrow.settleShippingFee(order, driver);

        assertTrue(again.alreadyApplied());
        assertEquals(0, driverAvailable(driver).compareTo(SHIPPING));
        assertEquals(0, suspenseAvailable().compareTo(suspenseBefore));
    }

    @Test
    void aCourierWhoDeliversBeforeTheReleaseIsPaidUnderTheOrdersShippingReference() {
        EscrowService escrow = escrow();
        String order = "ORD-SETTLE-" + UUID.randomUUID();
        String driver = UUID.randomUUID().toString();
        holdWithoutADriver(escrow, order);

        escrow.settleShippingFee(order, driver);
        escrow.releaseEscrow(order);

        assertEquals(0, driverAvailable(driver).compareTo(SHIPPING));
        assertEquals(driver, ledger("escrow:shipping:" + order).principalId());
        assertTrue(paymentTransactionRepository.findByReferenceNumber("escrow:shipping:" + order + ":paid")
            .isEmpty()
        );
    }

    @Test
    void aReleaseThatKeptNoFeeIsNotPaidFromTheFeesKeptForOtherOrders() {
        EscrowService escrow = escrow();
        String kept = "ORD-SETTLE-" + UUID.randomUUID();
        holdWithoutADriver(escrow, kept);
        escrow.releaseEscrow(kept);
        String legacy = "ORD-SETTLE-" + UUID.randomUUID();
        transactionRunner.inNewTransaction(() -> escrowRepository.save(new EscrowHold(
            null, legacy, UUID.randomUUID().toString(), UUID.randomUUID().toString(), null,
            TOTAL, MERCHANT_AMOUNT, SHIPPING, EscrowHold.EscrowStatus.RELEASED,
            Instant.now(), Instant.now(), null, BigDecimal.ZERO
        )));
        String driver = UUID.randomUUID().toString();
        BigDecimal suspenseBefore = suspenseAvailable();

        assertThrows(DomainException.class, () -> escrow.settleShippingFee(legacy, driver));

        assertEquals(0, suspenseAvailable().compareTo(suspenseBefore));
        assertEquals(0, driverAvailable(driver).compareTo(BigDecimal.ZERO));
        assertFalse(escrowRepository.findByOrderNumber(legacy).orElseThrow().shippingFeeSettled());
    }

    private EscrowService escrow() {
        return new EscrowService(escrowRepository, customerWalletRepository, merchantWalletRepository,
            driverWalletRepository, suspenseWalletRepository, paymentTransactionRepository,
            idempotencyRepository, transactionRunner, advisoryLock
        );
    }

    private void holdWithoutADriver(EscrowService escrow, String order) {
        String customer = UUID.randomUUID().toString();
        String merchant = UUID.randomUUID().toString();
        transactionRunner.inNewTransaction(() -> customerWalletRepository.save(
            CustomerWallet.createInitial(customer).topUp(TOTAL)
        ));
        escrow.createEscrowHold(new CreateEscrowHoldCommand(order, customer, merchant, null,
            TOTAL, MERCHANT_AMOUNT, SHIPPING, "k-" + order,
            EscrowRequestFingerprint.forCreateHold(order, customer, merchant, null,
                TOTAL, MERCHANT_AMOUNT, SHIPPING
            )
        ));
    }

    private BigDecimal driverAvailable(String driver) {
        return driverWalletRepository.findByDriverPrincipalId(driver)
            .map(DriverWallet::availableBalance)
            .orElse(BigDecimal.ZERO);
    }

    private BigDecimal suspenseAvailable() {
        return suspenseWalletRepository.findByPurpose(SuspenseWallet.UNASSIGNED_DRIVER_SHIPPING_FEE)
            .map(SuspenseWallet::availableBalance)
            .orElse(BigDecimal.ZERO);
    }

    private PaymentTransaction ledger(String reference) {
        return paymentTransactionRepository.findByReferenceNumber(reference).orElseThrow(
            () -> new AssertionError("no ledger entry under " + reference)
        );
    }
}
