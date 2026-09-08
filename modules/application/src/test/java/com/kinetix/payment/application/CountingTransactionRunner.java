package com.kinetix.payment.application;

import com.kinetix.payment.domain.port.TransactionRunnerPort;
import java.util.function.Supplier;

final class CountingTransactionRunner implements TransactionRunnerPort {
    private int transactions;
    private int depth;

    @Override
    public <T> T inNewTransaction(Supplier<T> work) {
        transactions++;
        depth++;
        try {
            return work.get();
        } finally {
            depth--;
        }
    }

    int transactions() {
        return transactions;
    }

    boolean insideTransaction() {
        return depth > 0;
    }
}
