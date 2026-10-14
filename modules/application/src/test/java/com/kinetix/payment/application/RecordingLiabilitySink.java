package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.LiabilityAccount;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class RecordingLiabilitySink implements LiabilitySnapshotSink {
    final List<String> events = new ArrayList<>();
    final List<LiabilityAccount> accounts = new ArrayList<>();
    UUID snapshotId;
    LiabilitySnapshotTotals totals;

    @Override
    public void begin(UUID snapshotId, Instant asOf, String currency) {
        events.add("begin " + asOf + " " + currency);
        this.snapshotId = snapshotId;
    }

    @Override
    public void account(LiabilityAccount account) {
        events.add("account " + account.kind());
        accounts.add(account);
    }

    @Override
    public void end(LiabilitySnapshotTotals totals) {
        events.add("end " + totals.accountCount());
        this.totals = totals;
    }
}
