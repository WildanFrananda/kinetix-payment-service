package com.kinetix.payment.domain.port;

public interface LiabilitySnapshotPort {
    void read(String currency, LiabilitySnapshotVisitor visitor);
}
