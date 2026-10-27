package com.kinetix.payment.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kinetix.payment.application.LiabilitySnapshotService;
import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.LiabilityHolder;
import com.kinetix.payment.domain.entity.LiabilitySnapshotRecord;
import com.kinetix.payment.domain.entity.MerchantWallet;
import com.kinetix.payment.domain.entity.SuspenseWallet;
import common.v1.Common;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import payment.v1.Liability;

class LiabilityGrpcServerServiceTest {
    private static final Instant AS_OF = Instant.parse("2026-10-12T09:00:00.123456Z");
    private static final String CUSTOMER = "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7";
    private static final String MERCHANT = "3c9a77b1-58de-4a01-8f2e-6d4b19c0a8f3";
    private static final Metadata.Key<Common.ErrorDetail> ERROR_DETAIL =
        ProtoUtils.keyForProto(Common.ErrorDetail.getDefaultInstance());

    private final FakeServerCallStreamObserver<Liability.StreamLiabilitySnapshotResponse> call =
        new FakeServerCallStreamObserver<>();
    private final KeptSnapshotRecords records = new KeptSnapshotRecords();

    @Test
    void theStreamIsAHeaderThenBatchesThenATrailerThatAddsUp() {
        List<CustomerWallet> customers = IntStream.range(0, 1201)
            .mapToObj(i -> CustomerWallet.createInitial(UUID.randomUUID().toString()).topUp(new BigDecimal("0.01")))
            .toList();

        serve(customers, "IDR");

        assertTrue(call.completed);
        assertNull(call.error);
        assertEquals(5, call.sent.size());
        Liability.LiabilitySnapshotHeader header = call.sent.get(0).getHeader();
        assertEquals(AS_OF.getEpochSecond(), header.getAsOf().getSeconds());
        assertEquals(AS_OF.getNano(), header.getAsOf().getNanos());
        assertEquals("IDR", header.getCurrency());
        assertEquals(
            List.of(500, 500, 201),
            call.sent.subList(1, 4).stream()
                .map(part -> part.getAccounts().getAccountsCount())
                .toList()
        );
        Liability.LiabilitySnapshotTrailer trailer = call.sent.get(4).getTrailer();
        assertEquals(1201, trailer.getAccountCount());
        assertEquals(Common.Money.newBuilder().setAmountMinor(1201).setCurrency("IDR").build(), trailer.getTotal());
        long streamed = call.sent.subList(1, 4).stream()
            .flatMap(part -> part.getAccounts().getAccountsList().stream())
            .mapToLong(account -> account.getBalance().getAmountMinor())
            .sum();
        assertEquals(trailer.getTotal().getAmountMinor(), streamed);
        LiabilitySnapshotRecord kept = records.kept.get(UUID.fromString(header.getSnapshotId()));
        assertEquals(1, records.kept.size());
        assertEquals(header.getSnapshotId(), kept.id().toString());
        assertEquals(AS_OF, kept.asOf());
        assertEquals(1201, kept.accountCount());
        assertEquals(new BigDecimal("12.01"), kept.total());
        assertEquals(32, trailer.getDigest().size());
        assertEquals(kept.digest(), HexFormat.of().formatHex(trailer.getDigest().toByteArray()));
    }

    @Test
    void aSnapshotThatCannotBeRecordedEndsWithoutATrailer() {
        records.failure = new DataAccessResourceFailureException("connection refused");

        serve(List.of(CustomerWallet.createInitial(CUSTOMER)), "IDR");

        assertFalse(call.completed);
        assertTrue(call.sent.stream().noneMatch(Liability.StreamLiabilitySnapshotResponse::hasTrailer));
        assertRefused(Status.Code.UNAVAILABLE, "LIABILITY_SNAPSHOT_UNAVAILABLE");
    }

    @Test
    void balancesCrossTheWireInExactMinorUnitsUnderTheRightHolder() {
        serve(
            List.of(
                CustomerWallet.createInitial(CUSTOMER).topUp(new BigDecimal("150000.00")),
                MerchantWallet.createInitial(MERCHANT).addPendingEscrow(new BigDecimal("90000000000000000.00")),
                SuspenseWallet.createInitial(SuspenseWallet.UNASSIGNED_DRIVER_SHIPPING_FEE)
            ),
            "IDR"
        );

        assertNull(call.error);
        List<Liability.LiabilityAccount> accounts = call.sent.get(1).getAccounts().getAccountsList();
        assertEquals(5, accounts.size());
        assertEquals(Liability.LiabilityKind.LIABILITY_KIND_CUSTOMER_BALANCE, accounts.get(0).getKind());
        assertEquals(CUSTOMER, accounts.get(0).getPrincipalId());
        assertEquals(15_000_000L, accounts.get(0).getBalance().getAmountMinor());
        assertEquals(Liability.LiabilityKind.LIABILITY_KIND_MERCHANT_PENDING_ESCROW, accounts.get(2).getKind());
        assertEquals(9_000_000_000_000_000_000L, accounts.get(2).getBalance().getAmountMinor());
        assertEquals(9_000_000_000_015_000_000L, call.sent.get(2).getTrailer().getTotal().getAmountMinor());
        assertEquals(Liability.LiabilityAccount.HolderCase.SUSPENSE_PURPOSE, accounts.get(3).getHolderCase());
        assertEquals(SuspenseWallet.UNASSIGNED_DRIVER_SHIPPING_FEE, accounts.get(3).getSuspensePurpose());
        assertEquals(Liability.LiabilityKind.LIABILITY_KIND_SUSPENSE_PENDING_ESCROW, accounts.get(4).getKind());
    }

    @Test
    void aTotalTooLargeForTheWireIsRefusedRatherThanWrapped() {
        serve(
            List.of(
                MerchantWallet.createInitial(MERCHANT).addPendingEscrow(new BigDecimal("92233720368547758.07")),
                CustomerWallet.createInitial(CUSTOMER).topUp(new BigDecimal("0.01"))
            ),
            "IDR"
        );

        assertFalse(call.completed);
        assertRefused(Status.Code.FAILED_PRECONDITION, "LIABILITY_SNAPSHOT_REFUSED");
    }

    @Test
    void aFractionOfAMinorUnitIsRefusedRatherThanRounded() {
        serve(List.of(CustomerWallet.createInitial(CUSTOMER).topUp(new BigDecimal("0.005"))), "IDR");

        assertFalse(call.completed);
        assertRefused(
            Status.Code.FAILED_PRECONDITION,
            "LIABILITY_SNAPSHOT_REFUSED",
            "the IDR liability snapshot was abandoned because the ledger could not be reported exactly; "
                + "payment's log names the cause"
        );
    }

    @Test
    void aMalformedCurrencyIsTheCallersMistake() {
        serve(List.of(), "rupiah");

        assertTrue(call.sent.isEmpty());
        assertRefused(
            Status.Code.INVALID_ARGUMENT,
            "INVALID_ARGUMENT",
            "a liability snapshot is taken in one ISO 4217 currency, and 'rupiah' is not one"
        );
    }

    @Test
    void anUnreadableLedgerIsUnavailableNotRefused() {
        ListedLiabilitySnapshots port = new ListedLiabilitySnapshots(
            AS_OF,
            List.of(),
            new DataAccessResourceFailureException("connection refused")
        );

        new LiabilityGrpcServerService(new LiabilitySnapshotService(port, records), Duration.ofSeconds(1))
            .streamLiabilitySnapshot(request("IDR"), call);

        assertRefused(
            Status.Code.UNAVAILABLE,
            "LIABILITY_SNAPSHOT_UNAVAILABLE",
            "the ledger could not be read for a liability snapshot; retry it"
        );
    }

    @Test
    void aCallerThatIsBrieflyBusyIsWaitedFor() {
        call.notReadyPolls = 3;

        serve(List.of(CustomerWallet.createInitial(CUSTOMER)), "IDR");

        assertTrue(call.completed);
        assertEquals(3, call.sent.size());
        assertTrue(call.readinessPolls >= 6);
    }

    @Test
    void aCallerThatStopsReadingReleasesTheSnapshot() {
        call.neverReady = true;
        ListedLiabilitySnapshots port = new ListedLiabilitySnapshots(
            AS_OF,
            List.of(CustomerWallet.createInitial(CUSTOMER), CustomerWallet.createInitial(MERCHANT)),
            null
        );

        new LiabilityGrpcServerService(new LiabilitySnapshotService(port, records), Duration.ofMillis(50))
            .streamLiabilitySnapshot(request("IDR"), call);

        assertTrue(call.sent.isEmpty());
        assertEquals(0, port.holdersRead);
        assertInstanceOf(StatusRuntimeException.class, call.error);
        assertEquals(Status.Code.DEADLINE_EXCEEDED, Status.fromThrowable(call.error).getCode());
    }

    @Test
    void aCallerThatHungUpIsNotAnsweredAndTheReadStops() {
        call.neverReady = true;
        ListedLiabilitySnapshots port = new ListedLiabilitySnapshots(
            AS_OF,
            List.of(CustomerWallet.createInitial(CUSTOMER)),
            null
        );
        Context.CancellableContext hungUp = Context.current().withCancellation();
        hungUp.cancel(null);

        hungUp.run(
            () -> new LiabilityGrpcServerService(new LiabilitySnapshotService(port, records), Duration.ofSeconds(5))
                .streamLiabilitySnapshot(request("IDR"), call)
        );

        assertTrue(call.sent.isEmpty());
        assertEquals(0, port.holdersRead);
        assertNull(call.error);
        assertFalse(call.completed);
    }

    private void serve(List<? extends LiabilityHolder> holders, String currency) {
        ListedLiabilitySnapshots port = new ListedLiabilitySnapshots(AS_OF, holders, null);
        new LiabilityGrpcServerService(new LiabilitySnapshotService(port, records), Duration.ofSeconds(1))
            .streamLiabilitySnapshot(request(currency), call);
    }

    private static Liability.StreamLiabilitySnapshotRequest request(String currency) {
        return Liability.StreamLiabilitySnapshotRequest.newBuilder().setCurrency(currency).build();
    }

    private void assertRefused(Status.Code code, String errorCode) {
        assertInstanceOf(StatusRuntimeException.class, call.error);
        StatusRuntimeException refused = (StatusRuntimeException) call.error;
        assertEquals(code, refused.getStatus().getCode());
        assertEquals(errorCode, refused.getTrailers().get(ERROR_DETAIL).getErrorCode());
    }

    private void assertRefused(Status.Code code, String errorCode, String description) {
        assertRefused(code, errorCode);
        StatusRuntimeException refused = (StatusRuntimeException) call.error;
        assertEquals(description, refused.getStatus().getDescription());
        assertEquals(description, refused.getTrailers().get(ERROR_DETAIL).getMessage());
    }
}
