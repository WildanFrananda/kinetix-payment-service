package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.entity.LiabilityAccount;
import com.kinetix.payment.domain.entity.LiabilityHolder;
import com.kinetix.payment.domain.port.LiabilitySnapshotVisitor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class SnapshotCapture implements LiabilitySnapshotVisitor {
    Instant asOf;
    final List<LiabilityAccount> accounts = new ArrayList<>();

    @Override
    public void asOf(Instant moment) {
        asOf = moment;
    }

    @Override
    public void holder(LiabilityHolder holder) {
        accounts.addAll(holder.liabilities());
    }

    List<LiabilityAccount> of(String holder) {
        return accounts.stream().filter(account -> account.holder().equals(holder)).toList();
    }

    BigDecimal owedTo(Set<String> holders) {
        return accounts.stream()
            .filter(account -> holders.contains(account.holder()))
            .map(LiabilityAccount::balance)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
