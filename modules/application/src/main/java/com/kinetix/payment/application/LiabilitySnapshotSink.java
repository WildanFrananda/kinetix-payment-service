package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.LiabilityAccount;
import java.time.Instant;
import java.util.UUID;

public interface LiabilitySnapshotSink {
    void begin(UUID snapshotId, Instant asOf, String currency);

    void account(LiabilityAccount account);

    void end(LiabilitySnapshotTotals totals);
}
