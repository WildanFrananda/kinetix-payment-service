package com.kinetix.payment.infrastructure.grpc;

import com.kinetix.payment.domain.entity.LiabilitySnapshotRecord;
import com.kinetix.payment.domain.port.LiabilitySnapshotRecordPort;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class KeptSnapshotRecords implements LiabilitySnapshotRecordPort {
    final Map<UUID, LiabilitySnapshotRecord> kept = new LinkedHashMap<>();
    RuntimeException failure;

    @Override
    public void save(LiabilitySnapshotRecord record) {
        if (failure != null) {
            throw failure;
        }

        kept.put(record.id(), record);
    }

    @Override
    public Optional<LiabilitySnapshotRecord> find(UUID id) {
        if (failure != null) {
            throw failure;
        }

        return Optional.ofNullable(kept.get(id));
    }
}
