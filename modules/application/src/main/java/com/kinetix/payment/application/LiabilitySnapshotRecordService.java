package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.LiabilitySnapshotRecord;
import com.kinetix.payment.domain.port.LiabilitySnapshotRecordPort;
import java.util.Optional;
import java.util.UUID;

public class LiabilitySnapshotRecordService {
    private final LiabilitySnapshotRecordPort records;

    public LiabilitySnapshotRecordService(LiabilitySnapshotRecordPort records) {
        this.records = records;
    }

    public Optional<LiabilitySnapshotRecord> find(String snapshotId) {
        return records.find(parse(snapshotId));
    }

    private static UUID parse(String snapshotId) {
        if (snapshotId == null) {
            throw new IllegalArgumentException("a snapshot record is asked for by its snapshot id");
        }

        UUID id;
        try {
            id = UUID.fromString(snapshotId);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("'" + snapshotId + "' is not a snapshot id", malformed);
        }

        if (!id.toString().equals(snapshotId)) {
            throw new IllegalArgumentException("'" + snapshotId + "' is not a snapshot id in canonical form");
        }

        return id;
    }
}
