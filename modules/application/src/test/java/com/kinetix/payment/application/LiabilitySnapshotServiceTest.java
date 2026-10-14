package com.kinetix.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.DriverWallet;
import com.kinetix.payment.domain.entity.LiabilityHolder;
import com.kinetix.payment.domain.entity.LiabilitySnapshotRecord;
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
    private static final String SECOND_CUSTOMER = "1b2c3d4e-0000-4000-8000-000000000001";

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

        RecordingSnapshotRecords records = new RecordingSnapshotRecords(sink.events);

        LiabilitySnapshotTotals totals = new LiabilitySnapshotService(port, records).export("IDR", sink);

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
                "record 7",
                "end 7"
            ),
            sink.events
        );
        assertEquals(sink.snapshotId, totals.snapshotId());
        assertEquals(AS_OF, totals.asOf());
        assertEquals("IDR", totals.currency());
        assertEquals(7, totals.accountCount());
        assertEquals(new BigDecimal("650000.00"), totals.total());
        assertSame(totals, sink.totals);
        assertEquals(
            List.of(
                new LiabilitySnapshotRecord(
                    totals.snapshotId(),
                    AS_OF,
                    "IDR",
                    7,
                    new BigDecimal("650000.00"),
                    totals.digest()
                )
            ),
            records.saved
        );
        assertEquals(List.of("IDR"), port.currenciesRead);
    }

    @Test
    @DisplayName("the digest is the one an independent SHA-256 of the canonical rows gives")
    void digestMatchesTheKnownAnswer() {
        ScriptedLiabilitySnapshots port = new ScriptedLiabilitySnapshots(
            Instant.parse("2026-10-12T09:00:00.123456Z"),
            List.of(
                CustomerWallet.createInitial(CUSTOMER).topUp(new BigDecimal("500000.00")),
                MerchantWallet.createInitial(MERCHANT).addPendingEscrow(new BigDecimal("130000.00")),
                DriverWallet.createInitial(DRIVER).addPendingEscrow(new BigDecimal("20000.00")),
                SuspenseWallet.createInitial(SuspenseWallet.UNASSIGNED_DRIVER_SHIPPING_FEE),
                CustomerWallet.createInitial(SECOND_CUSTOMER).topUp(new BigDecimal("0.01"))
            ),
            null
        );

        LiabilitySnapshotTotals totals = new LiabilitySnapshotService(port, new RecordingSnapshotRecords())
            .export("IDR", new RecordingLiabilitySink());

        assertEquals(8, totals.accountCount());
        assertEquals("89276c0d19d674863ff54989337008621a5774b1a5226f6fbd5029b30bb0bd8f", totals.digest());
    }

    @Test
    @DisplayName("an empty ledger has a digest too, of the header alone")
    void emptyLedgerDigestMatchesTheKnownAnswer() {
        LiabilitySnapshotTotals totals = new LiabilitySnapshotService(port(List.of()), new RecordingSnapshotRecords())
            .export("IDR", new RecordingLiabilitySink());

        assertEquals("16956e78b53678e1767f5d74d09d4ba7884ed4cc1109f6ae47f9113baa1cde49", totals.digest());
    }

    @Test
    @DisplayName("a snapshot that cannot be recorded never sends its trailer")
    void unrecordedSnapshotHasNoTrailer() {
        RecordingLiabilitySink sink = new RecordingLiabilitySink();
        RecordingSnapshotRecords records = new RecordingSnapshotRecords(sink.events);
        UncheckedIOException unreachable = new UncheckedIOException(new IOException("connection reset"));
        records.failure = unreachable;

        UncheckedIOException thrown = assertThrows(
            UncheckedIOException.class,
            () -> new LiabilitySnapshotService(port(List.of()), records).export("IDR", sink)
        );

        assertSame(unreachable, thrown);
        assertNull(sink.totals);
        assertTrue(sink.events.stream().noneMatch(event -> event.startsWith("end")));
    }

    @Test
    @DisplayName("every snapshot is recorded under an id of its own, the one its header carried")
    void everySnapshotHasItsOwnId() {
        RecordingSnapshotRecords records = new RecordingSnapshotRecords();
        LiabilitySnapshotService service = new LiabilitySnapshotService(port(List.of()), records);
        RecordingLiabilitySink first = new RecordingLiabilitySink();
        RecordingLiabilitySink second = new RecordingLiabilitySink();

        service.export("IDR", first);
        service.export("IDR", second);

        assertNotEquals(first.snapshotId, second.snapshotId);
        assertEquals(List.of(first.snapshotId, second.snapshotId), records.saved.stream().map(LiabilitySnapshotRecord::id).toList());
    }

    @Test
    @DisplayName("an empty ledger is still a snapshot: dated, no balances, nothing owed")
    void emptyLedgerIsStillASnapshot() {
        RecordingLiabilitySink sink = new RecordingLiabilitySink();

        LiabilitySnapshotTotals totals = new LiabilitySnapshotService(port(List.of()), new RecordingSnapshotRecords()).export("IDR", sink);

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
                () -> new LiabilitySnapshotService(port, new RecordingSnapshotRecords()).export(currency, new RecordingLiabilitySink())
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
            () -> new LiabilitySnapshotService(port(List.of(dollars)), new RecordingSnapshotRecords()).export("IDR", sink)
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
            () -> new LiabilitySnapshotService(port(List.of(held)), new RecordingSnapshotRecords()).export("IDR", sink)
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
            () -> new LiabilitySnapshotService(undated, new RecordingSnapshotRecords()).export("IDR", sink)
        );
        assertThrows(
            LiabilitySnapshotRefusedException.class,
            () -> new LiabilitySnapshotService(readBeforeDated, new RecordingSnapshotRecords()).export("IDR", sink)
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
            () -> new LiabilitySnapshotService(port, new RecordingSnapshotRecords()).export("IDR", sink)
        );

        assertSame(unreachable, thrown);
        assertNull(sink.totals);
    }

    private static ScriptedLiabilitySnapshots port(List<LiabilityHolder> holders) {
        return new ScriptedLiabilitySnapshots(AS_OF, holders, null);
    }
}
