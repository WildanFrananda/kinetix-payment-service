package com.kinetix.payment.domain.port;

import com.kinetix.payment.domain.entity.LiabilitySnapshotRecord;
import java.util.Optional;
import java.util.UUID;

public interface LiabilitySnapshotRecordPort {
    void save(LiabilitySnapshotRecord record);

    Optional<LiabilitySnapshotRecord> find(UUID id);
}
