package com.kinetix.payment.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LiabilitySnapshotRecordJpaRepository extends JpaRepository<LiabilitySnapshotRecordJpaEntity, UUID> {}
