package com.kinetix.payment.application;

import com.kinetix.payment.domain.port.TransactionRunnerPort;
import java.util.function.Supplier;

final class DirectTransactionRunner implements TransactionRunnerPort {
    @Override
    public <T> T inNewTransaction(Supplier<T> work) {
        return work.get();
    }
}
