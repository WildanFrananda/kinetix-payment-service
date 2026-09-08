package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.EscrowHold;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EscrowRequestFingerprintTest {
    private static final String ORDER = "ORD-1001";
    private static final String CUSTOMER = "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7";
    private static final String MERCHANT = "3c9a77b1-58de-4a01-8f2e-6d4b19c0a8f3";
    private static final String DRIVER = "b7e2c05f-9a34-4c88-b1d6-0e7a3f52d914";

    @Test
    void scaleDoesNotChangeTheFingerprint() {
        assertNotEquals(new BigDecimal("150000"), new BigDecimal("150000.00"));

        assertEquals(
            fingerprintOf(new BigDecimal("150000")),
            fingerprintOf(new BigDecimal("150000.00")
        ));
    }

    @Test
    void aFingerprintRebuiltFromAStoredHoldMatchesTheOneTakenOffTheWire() {
        String fromWire = EscrowRequestFingerprint.forCreateHold(
            ORDER, CUSTOMER, MERCHANT, DRIVER, 15_000_000L, 13_000_000L, 2_000_000L, "IDR");

        String fromRow = EscrowRequestFingerprint.forCreateHold(storedHold());

        assertEquals(fromWire, fromRow);
    }

    @Test
    void anAbsentCurrencyMeansTheSameThingAsIDR() {
        assertEquals(
            EscrowRequestFingerprint.forCreateHold(
                ORDER, CUSTOMER, MERCHANT, DRIVER, 15_000_000L, 13_000_000L, 2_000_000L, ""),
            EscrowRequestFingerprint.forCreateHold(
                ORDER, CUSTOMER, MERCHANT, DRIVER, 15_000_000L, 13_000_000L, 2_000_000L, "IDR")
        );
    }

    @Test
    void aDifferentCurrencyIsADifferentRequest() {
        assertNotEquals(
            EscrowRequestFingerprint.forCreateHold(
                ORDER, CUSTOMER, MERCHANT, DRIVER, 15_000_000L, 13_000_000L, 2_000_000L, "IDR"),
            EscrowRequestFingerprint.forCreateHold(
                ORDER, CUSTOMER, MERCHANT, DRIVER, 15_000_000L, 13_000_000L, 2_000_000L, "USD")
        );
    }

    @Test
    void aDifferentAmountIsADifferentRequest() {
        assertNotEquals(fingerprintOf(new BigDecimal("150000.00")), fingerprintOf(new BigDecimal("500000.00")));
    }

    @Test
    void movingMoneyBetweenTheMerchantAndShippingIsADifferentRequest() {
        assertNotEquals(
            EscrowRequestFingerprint.forCreateHold(
                ORDER, CUSTOMER, MERCHANT, DRIVER, 15_000_000L, 13_000_000L, 2_000_000L, "IDR"),
            EscrowRequestFingerprint.forCreateHold(
                ORDER, CUSTOMER, MERCHANT, DRIVER, 15_000_000L, 12_000_000L, 3_000_000L, "IDR")
        );
    }

    @Test
    void anAbsentDriverAndABlankDriverAreTheSameRequest() {
        assertEquals(
            EscrowRequestFingerprint.forCreateHold(
                ORDER, CUSTOMER, MERCHANT, null, 15_000_000L, 13_000_000L, 2_000_000L, "IDR"),
            EscrowRequestFingerprint.forCreateHold(
                ORDER, CUSTOMER, MERCHANT, "", 15_000_000L, 13_000_000L, 2_000_000L, "IDR")
        );
    }

    @Test
    void releaseAndRefundOfOneOrderAreDifferentRequests() {
        assertNotEquals(
            EscrowRequestFingerprint.forRelease(ORDER),
            EscrowRequestFingerprint.forRefund(ORDER)
        );
    }

    @Test
    void aFingerprintIsAHexSha256() {
        assertEquals(64, EscrowRequestFingerprint.forRelease(ORDER).length());
        assertEquals(
            EscrowRequestFingerprint.forRelease(ORDER),
            EscrowRequestFingerprint.forRelease(ORDER)
        );
    }

    private static String fingerprintOf(BigDecimal total) {
        return EscrowRequestFingerprint.forCreateHold(
            ORDER, CUSTOMER, MERCHANT, DRIVER, total,
            new BigDecimal("130000.00"), new BigDecimal("20000.00")
        );
    }

    private static EscrowHold storedHold() {
        return new EscrowHold(
            42L, ORDER, CUSTOMER, MERCHANT, DRIVER,
            new BigDecimal("150000.00"), new BigDecimal("130000.00"), new BigDecimal("20000.00"),
            EscrowHold.EscrowStatus.HELD, Instant.now().plusSeconds(3600), Instant.now(), null
        );
    }
}
