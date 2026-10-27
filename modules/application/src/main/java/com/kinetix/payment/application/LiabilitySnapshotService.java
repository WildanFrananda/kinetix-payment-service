package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.LiabilitySnapshotRecord;
import com.kinetix.payment.domain.exception.InvalidArgumentException;
import com.kinetix.payment.domain.exception.LiabilitySnapshotRefusedException;
import com.kinetix.payment.domain.port.LiabilitySnapshotPort;
import com.kinetix.payment.domain.port.LiabilitySnapshotRecordPort;
import java.util.UUID;
import java.util.regex.Pattern;

public class LiabilitySnapshotService {
    private static final Pattern ISO_CURRENCY = Pattern.compile("[A-Z]{3}");

    private final LiabilitySnapshotPort snapshots;
    private final LiabilitySnapshotRecordPort records;

    public LiabilitySnapshotService(LiabilitySnapshotPort snapshots, LiabilitySnapshotRecordPort records) {
        this.snapshots = snapshots;
        this.records = records;
    }

    public LiabilitySnapshotTotals export(String currency, LiabilitySnapshotSink sink) {
        if (currency == null || !ISO_CURRENCY.matcher(currency).matches()) {
            throw new InvalidArgumentException(
                "a liability snapshot is taken in one ISO 4217 currency, and '" + currency + "' is not one"
            );
        }

        LiabilitySnapshotTally tally = new LiabilitySnapshotTally(UUID.randomUUID(), currency, sink);
        LiabilitySnapshotTotals totals;
        try {
            snapshots.read(currency, tally);
            totals = tally.totals();
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException inconsistent) {
            throw new LiabilitySnapshotRefusedException(
                "the " + currency + " liability snapshot was abandoned because the ledger could not be reported "
                    + "exactly; payment's log names the cause",
                inconsistent
            );
        }

        records.save(
            new LiabilitySnapshotRecord(
                totals.snapshotId(),
                totals.asOf(),
                totals.currency(),
                totals.accountCount(),
                totals.total(),
                totals.digest()
            )
        );
        sink.end(totals);
        return totals;
    }
}
