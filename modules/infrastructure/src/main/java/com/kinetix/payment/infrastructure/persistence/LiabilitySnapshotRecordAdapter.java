package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.entity.LiabilitySnapshotRecord;
import com.kinetix.payment.domain.port.LiabilitySnapshotRecordPort;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class LiabilitySnapshotRecordAdapter implements LiabilitySnapshotRecordPort {
    private final LiabilitySnapshotRecordJpaRepository jpaRepository;

    public LiabilitySnapshotRecordAdapter(LiabilitySnapshotRecordJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void save(LiabilitySnapshotRecord record) {
        jpaRepository.saveAndFlush(
            new LiabilitySnapshotRecordJpaEntity(
                record.id(),
                record.asOf(),
                record.currency(),
                record.accountCount(),
                record.total(),
                HexFormat.of().parseHex(record.digest())
            )
        );
    }

    @Override
    public Optional<LiabilitySnapshotRecord> find(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    private LiabilitySnapshotRecord toDomain(LiabilitySnapshotRecordJpaEntity entity) {
        return new LiabilitySnapshotRecord(
            entity.getId(),
            entity.getAsOf(),
            entity.getCurrency(),
            entity.getAccountCount(),
            entity.getTotal(),
            HexFormat.of().formatHex(entity.getDigest())
        );
    }
}
