package com.kinetix.payment.infrastructure.grpc;

import com.kinetix.payment.application.LiabilitySnapshotService;
import com.kinetix.payment.application.LiabilitySnapshotTotals;
import com.kinetix.payment.domain.exception.LiabilitySnapshotRefusedException;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import jakarta.persistence.PersistenceException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.TransactionException;
import payment.v1.Liability;
import payment.v1.LiabilityServiceGrpc;

@GrpcService
public class LiabilityGrpcServerService extends LiabilityServiceGrpc.LiabilityServiceImplBase {
    private static final Logger LOG = LoggerFactory.getLogger(LiabilityGrpcServerService.class);

    private static final Duration STALL_LIMIT = Duration.ofSeconds(30);

    private final LiabilitySnapshotService snapshots;
    private final Duration stallLimit;

    @Autowired
    public LiabilityGrpcServerService(LiabilitySnapshotService snapshots) {
        this(snapshots, STALL_LIMIT);
    }

    LiabilityGrpcServerService(LiabilitySnapshotService snapshots, Duration stallLimit) {
        this.snapshots = snapshots;
        this.stallLimit = stallLimit;
    }

    @Override
    public void streamLiabilitySnapshot(
        Liability.StreamLiabilitySnapshotRequest request,
        StreamObserver<Liability.StreamLiabilitySnapshotResponse> responseObserver
    ) {
        ServerCallStreamObserver<Liability.StreamLiabilitySnapshotResponse> call =
            (ServerCallStreamObserver<Liability.StreamLiabilitySnapshotResponse>) responseObserver;
        Context context = Context.current();
        LiabilitySnapshotTotals totals;
        try {
            totals = snapshots.export(
                request.getCurrency(),
                new GrpcLiabilitySnapshotStream(call, context, stallLimit)
            );
        } catch (RuntimeException failure) {
            if (context.isCancelled()) {
                LOG.info("liability snapshot abandoned: the caller hung up");
                return;
            }
            call.onError(toStatusException(failure));
            return;
        }
        LOG.info("liability snapshot as of {} delivered: {} accounts owed {} {}",
            totals.asOf(), totals.accountCount(), totals.total().toPlainString(), totals.currency()
        );
        call.onCompleted();
    }

    private static StatusRuntimeException toStatusException(RuntimeException failure) {
        if (failure instanceof StatusRuntimeException status) {
            LOG.warn("liability snapshot abandoned: {}", status.getStatus().getDescription());
            return status;
        }
        if (failure instanceof IllegalArgumentException invalid) {
            return GrpcRefusal.of(Status.INVALID_ARGUMENT, "INVALID_ARGUMENT", invalid);
        }
        if (failure instanceof LiabilitySnapshotRefusedException refused) {
            LOG.error("liability snapshot refused: {}", refused.getMessage());
            return GrpcRefusal.of(Status.FAILED_PRECONDITION, "LIABILITY_SNAPSHOT_REFUSED", refused);
        }
        if (failure instanceof DataAccessException
            || failure instanceof TransactionException
            || failure instanceof PersistenceException
        ) {
            LOG.warn("liability snapshot could not read the ledger", failure);
            return GrpcRefusal.of(Status.UNAVAILABLE, "LIABILITY_SNAPSHOT_UNAVAILABLE", failure);
        }
        LOG.error("liability snapshot failed", failure);
        return Status.INTERNAL
            .withDescription("the liability snapshot could not be completed")
            .asRuntimeException();
    }
}
