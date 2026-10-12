package com.kinetix.payment.infrastructure.grpc;

import com.google.protobuf.Timestamp;
import com.kinetix.payment.application.LiabilitySnapshotSink;
import com.kinetix.payment.application.LiabilitySnapshotTotals;
import com.kinetix.payment.domain.entity.LiabilityAccount;
import com.kinetix.payment.domain.entity.LiabilityKind;
import common.v1.Common;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import payment.v1.Liability;

final class GrpcLiabilitySnapshotStream implements LiabilitySnapshotSink {
    static final int BATCH_SIZE = 500;

    private static final BigDecimal MINOR_PER_MAJOR = new BigDecimal("100");
    private static final long FIRST_PAUSE_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long LONGEST_PAUSE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private final ServerCallStreamObserver<Liability.StreamLiabilitySnapshotResponse> call;
    private final Context context;
    private final Duration stallLimit;
    private Liability.LiabilityAccountBatch.Builder batch = Liability.LiabilityAccountBatch.newBuilder();

    GrpcLiabilitySnapshotStream(
        ServerCallStreamObserver<Liability.StreamLiabilitySnapshotResponse> call,
        Context context,
        Duration stallLimit
    ) {
        this.call = call;
        this.context = context;
        this.stallLimit = stallLimit;
    }

    @Override
    public void begin(Instant asOf, String currency) {
        send(Liability.StreamLiabilitySnapshotResponse.newBuilder()
            .setHeader(Liability.LiabilitySnapshotHeader.newBuilder()
                .setAsOf(timestamp(asOf))
                .setCurrency(currency)
            )
            .build()
        );
    }

    @Override
    public void account(LiabilityAccount account) {
        Liability.LiabilityAccount.Builder wire = Liability.LiabilityAccount.newBuilder()
            .setKind(kindOf(account.kind()))
            .setBalance(money(account.balance(), account.currency()));
        if (account.ownedByPrincipal()) {
            wire.setPrincipalId(account.holder());
        } else {
            wire.setSuspensePurpose(account.holder());
        }
        batch.addAccounts(wire);
        if (batch.getAccountsCount() >= BATCH_SIZE) {
            flush();
        }
    }

    @Override
    public void end(LiabilitySnapshotTotals totals) {
        flush();
        send(Liability.StreamLiabilitySnapshotResponse.newBuilder()
            .setTrailer(Liability.LiabilitySnapshotTrailer.newBuilder()
                .setAccountCount(totals.accountCount())
                .setTotal(money(totals.total(), totals.currency()))
            )
            .build()
        );
    }

    private void flush() {
        if (batch.getAccountsCount() == 0) {
            return;
        }
        send(Liability.StreamLiabilitySnapshotResponse.newBuilder().setAccounts(batch).build());
        batch = Liability.LiabilityAccountBatch.newBuilder();
    }

    private void send(Liability.StreamLiabilitySnapshotResponse part) {
        awaitReady();
        call.onNext(part);
    }

    private void awaitReady() {
        long started = System.nanoTime();
        long pause = FIRST_PAUSE_NANOS;
        while (!call.isReady()) {
            if (context.isCancelled() || call.isCancelled()) {
                throw Status.CANCELLED
                    .withDescription("the caller hung up before the liability snapshot was delivered")
                    .asRuntimeException();
            }
            if (System.nanoTime() - started > stallLimit.toNanos()) {
                throw Status.DEADLINE_EXCEEDED
                    .withDescription("the caller read nothing for " + stallLimit + ", so the snapshot was abandoned")
                    .asRuntimeException();
            }
            LockSupport.parkNanos(pause);
            pause = Math.min(pause * 2, LONGEST_PAUSE_NANOS);
        }
    }

    private static Common.Money money(BigDecimal amount, String currency) {
        return Common.Money.newBuilder()
            .setAmountMinor(amount.multiply(MINOR_PER_MAJOR).setScale(0, RoundingMode.UNNECESSARY).longValueExact())
            .setCurrency(currency)
            .build();
    }

    private static Timestamp timestamp(Instant moment) {
        return Timestamp.newBuilder()
            .setSeconds(moment.getEpochSecond())
            .setNanos(moment.getNano())
            .build();
    }

    private static Liability.LiabilityKind kindOf(LiabilityKind kind) {
        return switch (kind) {
            case CUSTOMER_BALANCE -> Liability.LiabilityKind.LIABILITY_KIND_CUSTOMER_BALANCE;
            case MERCHANT_AVAILABLE -> Liability.LiabilityKind.LIABILITY_KIND_MERCHANT_AVAILABLE;
            case MERCHANT_PENDING_ESCROW -> Liability.LiabilityKind.LIABILITY_KIND_MERCHANT_PENDING_ESCROW;
            case DRIVER_AVAILABLE -> Liability.LiabilityKind.LIABILITY_KIND_DRIVER_AVAILABLE;
            case DRIVER_PENDING_ESCROW -> Liability.LiabilityKind.LIABILITY_KIND_DRIVER_PENDING_ESCROW;
            case SUSPENSE_AVAILABLE -> Liability.LiabilityKind.LIABILITY_KIND_SUSPENSE_AVAILABLE;
            case SUSPENSE_PENDING_ESCROW -> Liability.LiabilityKind.LIABILITY_KIND_SUSPENSE_PENDING_ESCROW;
        };
    }
}
