package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.LiabilitySnapshotRecord;
import com.kinetix.payment.domain.port.LiabilitySnapshotRecordPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class RecordingSnapshotRecords implements LiabilitySnapshotRecordPort {
    private final List<String> events;
    final List<LiabilitySnapshotRecord> saved = new ArrayList<>();
    RuntimeException failure;

    RecordingSnapshotRecords(List<String> events) {
        this.events = events;
    }

    RecordingSnapshotRecords() {
        this(new ArrayList<>());
    }

    @Override
    public void save(LiabilitySnapshotRecord record) {
        if (failure != null) {
            throw failure;
        }

        events.add("record " + record.accountCount());
        saved.add(record);
    }

    @Override
    public Optional<LiabilitySnapshotRecord> find(UUID id) {
        return saved.stream().filter(record -> record.id().equals(id)).findFirst();
    }
}
