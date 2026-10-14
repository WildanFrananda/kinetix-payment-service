package com.kinetix.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kinetix.payment.domain.entity.LiabilitySnapshotRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LiabilitySnapshotRecordServiceTest {
    private static final UUID SNAPSHOT = UUID.fromString("5d0c7a52-8e1b-4f6a-9c33-0b7e2d41a9f8");
    private static final String DIGEST = "16956e78b53678e1767f5d74d09d4ba7884ed4cc1109f6ae47f9113baa1cde49";

    @Test
    @DisplayName("a recorded snapshot is found by its id, and an unrecorded one is not")
    void recordsAreFoundByTheirId() {
        RecordingSnapshotRecords records = new RecordingSnapshotRecords();
        LiabilitySnapshotRecord record = new LiabilitySnapshotRecord(
            SNAPSHOT,
            Instant.parse("2026-10-12T09:00:00Z"),
            "IDR",
            0,
            BigDecimal.ZERO,
            DIGEST
        );
        records.save(record);
        LiabilitySnapshotRecordService service = new LiabilitySnapshotRecordService(records);

        assertEquals(Optional.of(record), service.find(SNAPSHOT.toString()));
        assertTrue(service.find(UUID.randomUUID().toString()).isEmpty());
    }

    @Test
    @DisplayName("an id is refused unless it is a UUID written exactly as the stream wrote it")
    void onlyCanonicalIdsAreAccepted() {
        LiabilitySnapshotRecordService service = new LiabilitySnapshotRecordService(new RecordingSnapshotRecords());

        for (String malformed : new String[] {null, "", "snapshot", SNAPSHOT.toString().toUpperCase(), "1-1-1-1-1"}) {
            assertThrows(IllegalArgumentException.class, () -> service.find(malformed), String.valueOf(malformed));
        }
    }
}
