package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.LiabilityHolder;
import com.kinetix.payment.domain.port.LiabilitySnapshotPort;
import com.kinetix.payment.domain.port.LiabilitySnapshotVisitor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class ScriptedLiabilitySnapshots implements LiabilitySnapshotPort {
    private final Instant asOf;
    private final List<LiabilityHolder> holders;
    private final RuntimeException failure;
    final List<String> currenciesRead = new ArrayList<>();

    ScriptedLiabilitySnapshots(Instant asOf, List<LiabilityHolder> holders, RuntimeException failure) {
        this.asOf = asOf;
        this.holders = holders;
        this.failure = failure;
    }

    @Override
    public void read(String currency, LiabilitySnapshotVisitor visitor) {
        currenciesRead.add(currency);
        if (asOf != null) {
            visitor.asOf(asOf);
        }

        for (LiabilityHolder holder : holders) {
            visitor.holder(holder);
        }

        if (failure != null) {
            throw failure;
        }
    }
}
