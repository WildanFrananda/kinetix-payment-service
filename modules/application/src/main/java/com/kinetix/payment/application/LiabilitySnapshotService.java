package com.kinetix.payment.application;

import com.kinetix.payment.domain.exception.LiabilitySnapshotRefusedException;
import com.kinetix.payment.domain.port.LiabilitySnapshotPort;
import java.util.regex.Pattern;

public class LiabilitySnapshotService {
    private static final Pattern ISO_CURRENCY = Pattern.compile("[A-Z]{3}");

    private final LiabilitySnapshotPort snapshots;

    public LiabilitySnapshotService(LiabilitySnapshotPort snapshots) {
        this.snapshots = snapshots;
    }

    public LiabilitySnapshotTotals export(String currency, LiabilitySnapshotSink sink) {
        if (currency == null || !ISO_CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException(
                "a liability snapshot is taken in one ISO 4217 currency, and '" + currency + "' is not one"
            );
        }

        LiabilitySnapshotTally tally = new LiabilitySnapshotTally(currency, sink);
        try {
            snapshots.read(currency, tally);
            LiabilitySnapshotTotals totals = tally.totals();
            sink.end(totals);
            return totals;
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException inconsistent) {
            throw new LiabilitySnapshotRefusedException(
                "the " + currency + " liability snapshot was abandoned: " + inconsistent.getMessage()
            );
        }
    }
}
