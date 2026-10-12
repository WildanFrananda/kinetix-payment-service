package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.LiabilityAccount;
import java.time.Instant;

public interface LiabilitySnapshotSink {
    void begin(Instant asOf, String currency);

    void account(LiabilityAccount account);

    void end(LiabilitySnapshotTotals totals);
}
