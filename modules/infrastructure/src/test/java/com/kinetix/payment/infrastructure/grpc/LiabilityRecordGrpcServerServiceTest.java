package com.kinetix.payment.infrastructure.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kinetix.payment.application.LiabilitySnapshotRecordService;
import com.kinetix.payment.domain.entity.LiabilitySnapshotRecord;
import common.v1.Common;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import payment.v1.LiabilityRecord;

class LiabilityRecordGrpcServerServiceTest {
    private static final UUID SNAPSHOT = UUID.fromString("5d0c7a52-8e1b-4f6a-9c33-0b7e2d41a9f8");
    private static final Instant AS_OF = Instant.parse("2026-10-12T09:00:00.123456Z");
    private static final String DIGEST = "89276c0d19d674863ff54989337008621a5774b1a5226f6fbd5029b30bb0bd8f";
    private static final Metadata.Key<Common.ErrorDetail> ERROR_DETAIL =
        ProtoUtils.keyForProto(Common.ErrorDetail.getDefaultInstance());

    private final KeptSnapshotRecords records = new KeptSnapshotRecords();
    private final FakeServerCallStreamObserver<LiabilityRecord.GetLiabilitySnapshotRecordResponse> call =
        new FakeServerCallStreamObserver<>();

    @Test
    void aKeptSnapshotIsAnsweredWithEverythingItsTrailerSaid() {
        records.kept.put(
            SNAPSHOT,
            new LiabilitySnapshotRecord(SNAPSHOT, AS_OF, "IDR", 8, new BigDecimal("650000.01"), DIGEST)
        );

        ask(SNAPSHOT.toString());

        assertTrue(call.completed);
        assertNull(call.error);
        LiabilityRecord.GetLiabilitySnapshotRecordResponse answer = call.sent.getFirst();
        assertEquals(SNAPSHOT.toString(), answer.getSnapshotId());
        assertEquals(AS_OF.getEpochSecond(), answer.getAsOf().getSeconds());
        assertEquals(AS_OF.getNano(), answer.getAsOf().getNanos());
        assertEquals("IDR", answer.getCurrency());
        assertEquals(8, answer.getAccountCount());
        assertEquals(Common.Money.newBuilder().setAmountMinor(65_000_001L).setCurrency("IDR").build(), answer.getTotal());
        assertEquals(DIGEST, HexFormat.of().formatHex(answer.getDigest().toByteArray()));
    }

    @Test
    void aSnapshotNeverTakenIsNotFound() {
        ask(SNAPSHOT.toString());

        assertFalse(call.completed);
        assertRefused(Status.Code.NOT_FOUND, "LIABILITY_SNAPSHOT_RECORD_NOT_FOUND");
    }

    @Test
    void anIdThatIsNotACanonicalUuidIsTheCallersMistake() {
        for (String malformed : new String[] {"", "not-a-uuid", SNAPSHOT.toString().toUpperCase(), "1-1-1-1-1"}) {
            FakeServerCallStreamObserver<LiabilityRecord.GetLiabilitySnapshotRecordResponse> each =
                new FakeServerCallStreamObserver<>();

            new LiabilityRecordGrpcServerService(new LiabilitySnapshotRecordService(records))
                .getLiabilitySnapshotRecord(request(malformed), each);

            assertInstanceOf(StatusRuntimeException.class, each.error, malformed);
            StatusRuntimeException refused = (StatusRuntimeException) each.error;
            assertEquals(Status.Code.INVALID_ARGUMENT, refused.getStatus().getCode(), malformed);
        }
    }

    @Test
    void anUnreadableStoreIsUnavailableNotNotFound() {
        records.failure = new DataAccessResourceFailureException("connection refused");

        ask(SNAPSHOT.toString());

        assertRefused(Status.Code.UNAVAILABLE, "LIABILITY_SNAPSHOT_RECORD_UNAVAILABLE");
    }

    private void ask(String snapshotId) {
        new LiabilityRecordGrpcServerService(new LiabilitySnapshotRecordService(records))
            .getLiabilitySnapshotRecord(request(snapshotId), call);
    }

    private static LiabilityRecord.GetLiabilitySnapshotRecordRequest request(String snapshotId) {
        return LiabilityRecord.GetLiabilitySnapshotRecordRequest.newBuilder().setSnapshotId(snapshotId).build();
    }

    private void assertRefused(Status.Code code, String errorCode) {
        assertInstanceOf(StatusRuntimeException.class, call.error);
        StatusRuntimeException refused = (StatusRuntimeException) call.error;
        assertEquals(code, refused.getStatus().getCode());
        assertEquals(errorCode, refused.getTrailers().get(ERROR_DETAIL).getErrorCode());
    }
}
