package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.LiabilityAccount;
import com.kinetix.payment.domain.entity.LiabilityHolder;
import com.kinetix.payment.domain.port.LiabilitySnapshotVisitor;
import java.math.BigDecimal;
import java.time.Instant;

final class LiabilitySnapshotTally implements LiabilitySnapshotVisitor {
    private final String currency;
    private final LiabilitySnapshotSink sink;
    private Instant asOf;
    private long accountCount;
    private BigDecimal total = BigDecimal.ZERO;

    LiabilitySnapshotTally(String currency, LiabilitySnapshotSink sink) {
        this.currency = currency;
        this.sink = sink;
    }

    @Override
    public void asOf(Instant moment) {
        if (moment == null) {
            throw new IllegalStateException("the snapshot did not say when it was taken");
        }

        if (asOf != null) {
            throw new IllegalStateException("the snapshot was dated twice, " + asOf + " and " + moment);
        }

        asOf = moment;
        sink.begin(moment, currency);
    }

    @Override
    public void holder(LiabilityHolder holder) {
        if (asOf == null) {
            throw new IllegalStateException("the snapshot read a balance before it was dated");
        }

        for (LiabilityAccount account : holder.liabilities()) {
            if (!currency.equals(account.currency())) {
                throw new IllegalStateException(
                    "a " + currency + " snapshot was handed the " + account.kind() + " of " + account.holder()
                        + " in " + account.currency()
                );
            }

            accountCount++;
            total = total.add(account.balance());
            sink.account(account);
        }
    }

    LiabilitySnapshotTotals totals() {
        if (asOf == null) {
            throw new IllegalStateException("the snapshot finished without ever being dated");
        }

        return new LiabilitySnapshotTotals(asOf, currency, accountCount, total);
    }
}
