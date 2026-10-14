package com.kinetix.payment.infrastructure.grpc;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.kinetix.payment.application.LiabilitySnapshotRecordService;
import com.kinetix.payment.domain.entity.LiabilitySnapshotRecord;
import com.kinetix.payment.domain.entity.MinorUnits;
import common.v1.Common;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import jakarta.persistence.PersistenceException;
import java.util.HexFormat;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.transaction.TransactionException;
import payment.v1.LiabilityRecord;
import payment.v1.LiabilityRecordServiceGrpc;

@GrpcService
public class LiabilityRecordGrpcServerService extends LiabilityRecordServiceGrpc.LiabilityRecordServiceImplBase {
    private static final Logger LOG = LoggerFactory.getLogger(LiabilityRecordGrpcServerService.class);

    private final LiabilitySnapshotRecordService records;

    public LiabilityRecordGrpcServerService(LiabilitySnapshotRecordService records) {
        this.records = records;
    }

    @Override
    public void getLiabilitySnapshotRecord(
        LiabilityRecord.GetLiabilitySnapshotRecordRequest request,
        StreamObserver<LiabilityRecord.GetLiabilitySnapshotRecordResponse> responseObserver
    ) {
        Optional<LiabilitySnapshotRecord> found;
        try {
            found = records.find(request.getSnapshotId());
        } catch (RuntimeException failure) {
            responseObserver.onError(toStatusException(failure));
            return;
        }

        if (found.isEmpty()) {
            responseObserver.onError(
                GrpcRefusal.of(
                    Status.NOT_FOUND,
                    "LIABILITY_SNAPSHOT_RECORD_NOT_FOUND",
                    new NoSuchElementException("no liability snapshot " + request.getSnapshotId() + " was ever taken")
                )
            );
            return;
        }

        responseObserver.onNext(toResponse(found.get()));
        responseObserver.onCompleted();
    }

    private static LiabilityRecord.GetLiabilitySnapshotRecordResponse toResponse(LiabilitySnapshotRecord record) {
        return LiabilityRecord.GetLiabilitySnapshotRecordResponse.newBuilder()
            .setSnapshotId(record.id().toString())
            .setAsOf(
                Timestamp.newBuilder()
                    .setSeconds(record.asOf().getEpochSecond())
                    .setNanos(record.asOf().getNano())
            )
            .setCurrency(record.currency())
            .setAccountCount(record.accountCount())
            .setTotal(
                Common.Money.newBuilder()
                    .setAmountMinor(MinorUnits.of(record.total()))
                    .setCurrency(record.currency())
            )
            .setDigest(ByteString.copyFrom(HexFormat.of().parseHex(record.digest())))
            .build();
    }

    private static StatusRuntimeException toStatusException(RuntimeException failure) {
        if (failure instanceof IllegalArgumentException invalid) {
            return GrpcRefusal.of(Status.INVALID_ARGUMENT, "INVALID_ARGUMENT", invalid);
        }

        if (failure instanceof DataAccessException
            || failure instanceof TransactionException
            || failure instanceof PersistenceException
        ) {
            LOG.warn("a liability snapshot record could not be read", failure);
            return GrpcRefusal.of(Status.UNAVAILABLE, "LIABILITY_SNAPSHOT_RECORD_UNAVAILABLE", failure);
        }

        LOG.error("a liability snapshot record could not be served", failure);
        return Status.INTERNAL
            .withDescription("the liability snapshot record could not be served")
            .asRuntimeException();
    }
}
