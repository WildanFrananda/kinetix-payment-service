package com.kinetix.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.kinetix.payment.domain.entity.LiabilityAccount;
import com.kinetix.payment.domain.entity.LiabilityKind;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LiabilitySnapshotDigestTest {
    private static final Instant AS_OF = Instant.parse("2026-10-12T09:00:00.123456Z");

    private static final List<LiabilityAccount> ACCOUNTS = List.of(
        account(LiabilityKind.CUSTOMER_BALANCE, "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7", new BigDecimal("500000.00")),
        account(LiabilityKind.MERCHANT_AVAILABLE, "3c9a77b1-58de-4a01-8f2e-6d4b19c0a8f3", BigDecimal.ZERO),
        account(LiabilityKind.MERCHANT_PENDING_ESCROW, "3c9a77b1-58de-4a01-8f2e-6d4b19c0a8f3", new BigDecimal("130000.00")),
        account(LiabilityKind.DRIVER_AVAILABLE, "b7e2c05f-9a34-4c88-b1d6-0e7a3f52d914", BigDecimal.ZERO),
        account(LiabilityKind.DRIVER_PENDING_ESCROW, "b7e2c05f-9a34-4c88-b1d6-0e7a3f52d914", new BigDecimal("20000.00")),
        account(LiabilityKind.SUSPENSE_AVAILABLE, "UNASSIGNED_DRIVER_SHIPPING_FEE", BigDecimal.ZERO),
        account(LiabilityKind.SUSPENSE_PENDING_ESCROW, "UNASSIGNED_DRIVER_SHIPPING_FEE", BigDecimal.ZERO),
        account(LiabilityKind.CUSTOMER_BALANCE, "1b2c3d4e-0000-4000-8000-000000000001", new BigDecimal("0.01"))
    );

    @Test
    @DisplayName("the rows are hashed in canonical order, so the order they were read in does not matter")
    void orderOfReadingDoesNotMatter() {
        LiabilitySnapshotDigest forward = new LiabilitySnapshotDigest();
        ACCOUNTS.forEach(forward::add);
        LiabilitySnapshotDigest backward = new LiabilitySnapshotDigest();
        ACCOUNTS.reversed().forEach(backward::add);

        String expected = "89276c0d19d674863ff54989337008621a5774b1a5226f6fbd5029b30bb0bd8f";
        assertEquals(expected, forward.digest(AS_OF, "IDR", 8, 65_000_001L));
        assertEquals(expected, backward.digest(AS_OF, "IDR", 8, 65_000_001L));
    }

    @Test
    @DisplayName("every field of the header is inside the digest")
    void everyHeaderFieldCounts() {
        LiabilitySnapshotDigest digest = new LiabilitySnapshotDigest();
        ACCOUNTS.forEach(digest::add);
        String base = digest.digest(AS_OF, "IDR", 8, 65_000_001L);

        assertNotEquals(base, digest.digest(AS_OF.plusNanos(1_000), "IDR", 8, 65_000_001L));
        assertNotEquals(base, digest.digest(AS_OF.plusSeconds(1), "IDR", 8, 65_000_001L));
        assertNotEquals(base, digest.digest(AS_OF, "USD", 8, 65_000_001L));
        assertNotEquals(base, digest.digest(AS_OF, "IDR", 9, 65_000_001L));
        assertNotEquals(base, digest.digest(AS_OF, "IDR", 8, 65_000_002L));
    }

    @Test
    @DisplayName("a holder name longer than the encoding can carry stops the snapshot")
    void overlongHolderIsRefused() {
        LiabilityAccount overlong = new LiabilityAccount(
            LiabilityKind.SUSPENSE_AVAILABLE,
            "S".repeat(256),
            BigDecimal.ZERO,
            "IDR"
        );

        assertThrows(IllegalStateException.class, () -> new LiabilitySnapshotDigest().add(overlong));
        new LiabilitySnapshotDigest().add(
            new LiabilityAccount(LiabilityKind.SUSPENSE_AVAILABLE, "S".repeat(255), BigDecimal.ZERO, "IDR")
        );
    }

    @Test
    @DisplayName("a fraction of a minor unit stops the snapshot rather than being rounded into the digest")
    void fractionalMinorUnitIsRefused() {
        LiabilityAccount fractional = new LiabilityAccount(
            LiabilityKind.CUSTOMER_BALANCE,
            "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7",
            new BigDecimal("0.005"),
            "IDR"
        );

        assertThrows(ArithmeticException.class, () -> new LiabilitySnapshotDigest().add(fractional));
    }

    private static LiabilityAccount account(LiabilityKind kind, String holder, BigDecimal balance) {
        return new LiabilityAccount(kind, holder, balance, "IDR");
    }
}
