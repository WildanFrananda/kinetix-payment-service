package com.kinetix.payment.infrastructure.grpc;

import com.kinetix.payment.application.EscrowService;
import com.kinetix.payment.domain.entity.EscrowHold;
import com.kinetix.payment.infrastructure.grpc.generated.CreateEscrowHoldRequest;
import com.kinetix.payment.infrastructure.grpc.generated.EscrowHoldResponse;
import com.kinetix.payment.infrastructure.grpc.generated.PaymentServiceGrpc;
import com.kinetix.payment.infrastructure.grpc.generated.ReleaseEscrowRequest;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import java.math.BigDecimal;

@GrpcService
public class PaymentGrpcServerService extends PaymentServiceGrpc.PaymentServiceImplBase {
    private final EscrowService escrowService;

    public PaymentGrpcServerService(EscrowService escrowService) {
        this.escrowService = escrowService;
    }

    @Override
    public void createEscrowHold(CreateEscrowHoldRequest request, StreamObserver<EscrowHoldResponse> responseObserver) {
        EscrowHold hold = escrowService.createEscrowHold(
            request.getOrderNumber(),
            request.getCustomerId(),
            request.getMerchantId(),
            request.getDriverId(),
            BigDecimal.valueOf(request.getTotalOrderAmount()),
            BigDecimal.valueOf(request.getMerchantAmount()),
            BigDecimal.valueOf(request.getShippingFeeAmount())
        );

        EscrowHoldResponse response = mapToProtoResponse(hold);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void releaseEscrow(ReleaseEscrowRequest request, StreamObserver<EscrowHoldResponse> responseObserver) {
        EscrowHold hold = escrowService.releaseEscrow(request.getOrderNumber());
        EscrowHoldResponse response = mapToProtoResponse(hold);
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private EscrowHoldResponse mapToProtoResponse(EscrowHold hold) {
        return EscrowHoldResponse.newBuilder()
            .setId(hold.id() != null ? hold.id() : 0)
            .setOrderNumber(hold.orderNumber())
            .setCustomerId(hold.customerId())
            .setMerchantId(hold.merchantId())
            .setDriverId(hold.driverId() != null ? hold.driverId() : 0)
            .setTotalOrderAmount(hold.totalOrderAmount().doubleValue())
            .setMerchantAmount(hold.merchantAmount().doubleValue())
            .setShippingFeeAmount(hold.shippingFeeAmount().doubleValue())
            .setStatus(hold.status().name())
            .setAutoReleaseAt(hold.autoReleaseAt() != null ? hold.autoReleaseAt().toString() : "")
            .setCreatedAt(hold.createdAt() != null ? hold.createdAt().toString() : "")
            .setReleasedAt(hold.releasedAt() != null ? hold.releasedAt().toString() : "")
            .build();
    }
}
