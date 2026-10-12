package com.kinetix.payment.domain.port;

import com.kinetix.payment.domain.entity.LiabilityHolder;
import java.time.Instant;

public interface LiabilitySnapshotVisitor {
    void asOf(Instant moment);

    void holder(LiabilityHolder holder);
}
