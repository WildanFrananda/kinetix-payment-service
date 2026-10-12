package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.LiabilityAccount;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class RecordingLiabilitySink implements LiabilitySnapshotSink {
    final List<String> events = new ArrayList<>();
    final List<LiabilityAccount> accounts = new ArrayList<>();
    LiabilitySnapshotTotals totals;

    @Override
    public void begin(Instant asOf, String currency) {
        events.add("begin " + asOf + " " + currency);
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
