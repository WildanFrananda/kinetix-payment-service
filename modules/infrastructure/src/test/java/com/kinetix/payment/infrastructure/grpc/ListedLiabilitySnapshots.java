package com.kinetix.payment.infrastructure.grpc;

import com.kinetix.payment.domain.entity.LiabilityHolder;
import com.kinetix.payment.domain.port.LiabilitySnapshotPort;
import com.kinetix.payment.domain.port.LiabilitySnapshotVisitor;
import java.time.Instant;
import java.util.List;

final class ListedLiabilitySnapshots implements LiabilitySnapshotPort {
    private final Instant asOf;
    private final List<? extends LiabilityHolder> holders;
    private final RuntimeException failure;
    int holdersRead;

    ListedLiabilitySnapshots(Instant asOf, List<? extends LiabilityHolder> holders, RuntimeException failure) {
        this.asOf = asOf;
        this.holders = holders;
        this.failure = failure;
    }

    @Override
    public void read(String currency, LiabilitySnapshotVisitor visitor) {
        if (failure != null) {
            throw failure;
        }

        visitor.asOf(asOf);
        for (LiabilityHolder holder : holders) {
            holdersRead++;
            visitor.holder(holder);
        }
    }
}
