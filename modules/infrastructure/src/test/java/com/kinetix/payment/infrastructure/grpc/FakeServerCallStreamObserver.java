package com.kinetix.payment.infrastructure.grpc;

import io.grpc.stub.ServerCallStreamObserver;
import java.util.ArrayList;
import java.util.List;

final class FakeServerCallStreamObserver<T> extends ServerCallStreamObserver<T> {
    final List<T> sent = new ArrayList<>();
    int notReadyPolls;
    int readinessPolls;
    boolean neverReady;
    boolean completed;
    Throwable error;

    @Override
    public boolean isReady() {
        readinessPolls++;
        if (neverReady) {
            return false;
        }

        if (notReadyPolls > 0) {
            notReadyPolls--;
            return false;
        }

        return true;
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public void setOnReadyHandler(Runnable onReadyHandler) {
    }

    @Override
    public void setOnCancelHandler(Runnable onCancelHandler) {
    }

    @Override
    public void setCompression(String compression) {
    }

    @Override
    public void disableAutoInboundFlowControl() {
    }

    @Override
    public void request(int count) {
    }

    @Override
    public void setMessageCompression(boolean enable) {
    }

    @Override
    public void onNext(T value) {
        sent.add(value);
    }

    @Override
    public void onError(Throwable throwable) {
        error = throwable;
    }

    @Override
    public void onCompleted() {
        completed = true;
    }
}
