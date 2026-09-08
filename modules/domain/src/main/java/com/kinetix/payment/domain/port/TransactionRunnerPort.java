package com.kinetix.payment.domain.port;

import java.util.function.Supplier;

public interface TransactionRunnerPort {
    <T> T inNewTransaction(Supplier<T> work);
}
