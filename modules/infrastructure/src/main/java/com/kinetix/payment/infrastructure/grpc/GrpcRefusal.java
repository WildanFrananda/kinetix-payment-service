package com.kinetix.payment.infrastructure.grpc;

import common.v1.Common;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;

final class GrpcRefusal {
    private static final Metadata.Key<Common.ErrorDetail> ERROR_DETAIL =
        ProtoUtils.keyForProto(Common.ErrorDetail.getDefaultInstance());

    private GrpcRefusal() {}

    static StatusRuntimeException of(Status status, String errorCode, Throwable cause) {
        String message = cause.getMessage() == null ? errorCode : cause.getMessage();
        Metadata trailers = new Metadata();
        trailers.put(ERROR_DETAIL, Common.ErrorDetail.newBuilder()
            .setErrorCode(errorCode)
            .setMessage(message)
            .build()
        );
        return status.withDescription(message).asRuntimeException(trailers);
    }
}
