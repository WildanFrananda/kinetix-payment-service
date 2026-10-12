package com.kinetix.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.DriverWallet;
import com.kinetix.payment.domain.entity.LiabilityHolder;
import com.kinetix.payment.domain.entity.MerchantWallet;
import com.kinetix.payment.domain.entity.SuspenseWallet;
import com.kinetix.payment.domain.exception.LiabilitySnapshotRefusedException;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LiabilitySnapshotServiceTest {
    private static final Instant AS_OF = Instant.parse("2026-10-12T09:00:00Z");
    private static final String CUSTOMER = "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7";
    private static final String MERCHANT = "3c9a77b1-58de-4a01-8f2e-6d4b19c0a8f3";
    private static final String DRIVER = "b7e2c05f-9a34-4c88-b1d6-0e7a3f52d914";

    @Test
    @DisplayName(
        "the snapshot is dated first, lists every balance in the order read, and closes with its own count and total"
    )
    void snapshotIsDatedListedAndTotalled() {
        RecordingLiabilitySink sink = new RecordingLiabilitySink();
        ScriptedLiabilitySnapshots port = port(
            List.of(
                CustomerWallet.createInitial(CUSTOMER).topUp(new BigDecimal("500000.00")),
                MerchantWallet.createInitial(MERCHANT).addPendingEscrow(new BigDecimal("130000.00")),
                DriverWallet.createInitial(DRIVER).addPendingEscrow(new BigDecimal("20000.00")),
                SuspenseWallet.createInitial(SuspenseWallet.UNASSIGNED_DRIVER_SHIPPING_FEE)
            )
        );

        LiabilitySnapshotTotals totals = new LiabilitySnapshotService(port).export("IDR", sink);

        assertEquals(
            List.of(
                "begin " + AS_OF + " IDR",
                "account CUSTOMER_BALANCE",
                "account MERCHANT_AVAILABLE",
                "account MERCHANT_PENDING_ESCROW",
                "account DRIVER_AVAILABLE",
                "account DRIVER_PENDING_ESCROW",
                "account SUSPENSE_AVAILABLE",
                "account SUSPENSE_PENDING_ESCROW",
                "end 7"
            ),
            sink.events
        );
        assertEquals(new LiabilitySnapshotTotals(AS_OF, "IDR", 7, new BigDecimal("650000.00")), totals);
        assertSame(totals, sink.totals);
        assertEquals(List.of("IDR"), port.currenciesRead);
    }

    @Test
    @DisplayName("an empty ledger is still a snapshot: dated, no balances, nothing owed")
    void emptyLedgerIsStillASnapshot() {
        RecordingLiabilitySink sink = new RecordingLiabilitySink();

        LiabilitySnapshotTotals totals = new LiabilitySnapshotService(port(List.of())).export("IDR", sink);

        assertEquals(List.of("begin " + AS_OF + " IDR", "end 0"), sink.events);
        assertEquals(0, totals.total().signum());
    }

    @Test
    @DisplayName("a currency that is not ISO 4217 is refused before anything is read")
    void malformedCurrencyIsRefusedBeforeReading() {
        for (String currency : new String[] {null, "", "idr", "ID", "IDRX", " IDR"}) {
            ScriptedLiabilitySnapshots port = port(List.of());

            assertThrows(
                IllegalArgumentException.class,
                () -> new LiabilitySnapshotService(port).export(currency, new RecordingLiabilitySink())
            );
            assertTrue(port.currenciesRead.isEmpty(), "read for '" + currency + "'");
        }
    }

    @Test
    @DisplayName("a balance in another currency means the source broke its promise, and the snapshot stops")
    void foreignCurrencyBalanceStopsTheSnapshot() {
        RecordingLiabilitySink sink = new RecordingLiabilitySink();
        LiabilityHolder dollars = new CustomerWallet(
            1L,
            CUSTOMER,
            BigDecimal.TEN,
            BigDecimal.ZERO,
            "USD",
            AS_OF,
            AS_OF
        );

        assertThrows(
            LiabilitySnapshotRefusedException.class,
            () -> new LiabilitySnapshotService(port(List.of(dollars))).export("IDR", sink)
        );
        assertNull(sink.totals);
    }

    @Test
    @DisplayName("a held balance nobody writes stops the snapshot rather than being quietly left out")
    void heldBalanceStopsTheSnapshot() {
        RecordingLiabilitySink sink = new RecordingLiabilitySink();
        LiabilityHolder held = new CustomerWallet(1L, CUSTOMER, BigDecimal.TEN, BigDecimal.ONE, "IDR", AS_OF, AS_OF);

        assertThrows(
            LiabilitySnapshotRefusedException.class,
            () -> new LiabilitySnapshotService(port(List.of(held))).export("IDR", sink)
        );
        assertNull(sink.totals);
    }

    @Test
    @DisplayName("a snapshot that was never dated is not a snapshot")
    void undatedSnapshotIsRefused() {
        RecordingLiabilitySink sink = new RecordingLiabilitySink();
        ScriptedLiabilitySnapshots undated = new ScriptedLiabilitySnapshots(null, List.of(), null);
        ScriptedLiabilitySnapshots readBeforeDated = new ScriptedLiabilitySnapshots(
            null,
            List.of(CustomerWallet.createInitial(CUSTOMER)),
            null
        );

        assertThrows(
            LiabilitySnapshotRefusedException.class,
            () -> new LiabilitySnapshotService(undated).export("IDR", sink)
        );
        assertThrows(
            LiabilitySnapshotRefusedException.class,
            () -> new LiabilitySnapshotService(readBeforeDated).export("IDR", sink)
        );
        assertNull(sink.totals);
    }

    @Test
    @DisplayName("a ledger that cannot be read is reported as that, never as a refused snapshot")
    void readFailureIsNotARefusal() {
        UncheckedIOException unreachable = new UncheckedIOException(new IOException("connection reset"));
        ScriptedLiabilitySnapshots port = new ScriptedLiabilitySnapshots(AS_OF, List.of(), unreachable);
        RecordingLiabilitySink sink = new RecordingLiabilitySink();

        UncheckedIOException thrown = assertThrows(
            UncheckedIOException.class,
            () -> new LiabilitySnapshotService(port).export("IDR", sink)
        );

        assertSame(unreachable, thrown);
        assertNull(sink.totals);
    }

    private static ScriptedLiabilitySnapshots port(List<LiabilityHolder> holders) {
        return new ScriptedLiabilitySnapshots(AS_OF, holders, null);
    }
}
