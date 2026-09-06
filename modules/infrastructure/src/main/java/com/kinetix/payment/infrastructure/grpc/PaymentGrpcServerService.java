package com.kinetix.payment.infrastructure.grpc;

import com.google.protobuf.Timestamp;
import com.kinetix.payment.application.EscrowService;
import com.kinetix.payment.domain.entity.EscrowHold;
import common.v1.Common;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import net.devh.boot.grpc.server.service.GrpcService;
import payment.v1.Payment;
import payment.v1.PaymentServiceGrpc;

@GrpcService
public class PaymentGrpcServerService extends PaymentServiceGrpc.PaymentServiceImplBase {

    private static final BigDecimal MINOR_PER_MAJOR = new BigDecimal("100");

    private final EscrowService escrowService;

    public PaymentGrpcServerService(EscrowService escrowService) {
        this.escrowService = escrowService;
    }

    @Override
    public void createEscrowHold(
        Payment.CreateEscrowHoldRequest request,
        StreamObserver<Payment.EscrowHoldResponse> responseObserver
    ) {
        responseObserver.onError(io.grpc.Status.UNIMPLEMENTED
            .withDescription(
                "CreateEscrowHold needs principal-keyed wallets, which land with the data model "
                    + "in S10; this service still keys escrow on account ids")
            .asRuntimeException());
    }

    @Override
    public void releaseEscrow(
        Payment.ReleaseEscrowRequest request,
        StreamObserver<Payment.EscrowHoldResponse> responseObserver
    ) {
        EscrowHold hold = escrowService.releaseEscrow(request.getOrderNumber());
        responseObserver.onNext(toResponse(hold));
        responseObserver.onCompleted();
    }

    @Override
    public void refundEscrow(
        Payment.RefundEscrowRequest request,
        StreamObserver<Payment.EscrowHoldResponse> responseObserver
    ) {
        responseObserver.onError(io.grpc.Status.UNIMPLEMENTED
            .withDescription("RefundEscrow lands with the saga's compensation in S11")
            .asRuntimeException());
    }

    @Override
    public void getEscrowStatus(
        Payment.GetEscrowStatusRequest request,
        StreamObserver<Payment.EscrowHoldResponse> responseObserver
    ) {
        EscrowHold hold = escrowService.findByOrderNumber(request.getOrderNumber());
        responseObserver.onNext(hold == null
            ? Payment.EscrowHoldResponse.newBuilder().setFound(false).build()
            : toResponse(hold));
        responseObserver.onCompleted();
    }

    private Payment.EscrowHoldResponse toResponse(EscrowHold hold) {
        Payment.EscrowHoldResponse.Builder builder =
            Payment.EscrowHoldResponse.newBuilder()
                .setFound(true)
                .setEscrowId(hold.id() == null ? "" : hold.id().toString())
                .setOrderNumber(hold.orderNumber())
                .setCustomerPrincipalId("")
                .setMerchantPrincipalId("")
                .setDriverPrincipalId("")
                .setTotalOrderAmount(toMoney(hold.totalOrderAmount()))
                .setMerchantAmount(toMoney(hold.merchantAmount()))
                .setShippingFeeAmount(toMoney(hold.shippingFeeAmount()))
                .setStatus(toStatus(hold.status().name()));

        if (hold.autoReleaseAt() != null) {
            builder.setAutoReleaseAt(toTimestamp(hold.autoReleaseAt()));
        }
        if (hold.createdAt() != null) {
            builder.setCreatedAt(toTimestamp(hold.createdAt()));
        }
        if (hold.releasedAt() != null) {
            builder.setReleasedAt(toTimestamp(hold.releasedAt()));
        }

        return builder.build();
    }

    private static Common.Money toMoney(BigDecimal amount) {
        BigDecimal minor = (amount == null ? BigDecimal.ZERO : amount)
            .multiply(MINOR_PER_MAJOR)
            .setScale(0, RoundingMode.HALF_UP);

        return Common.Money.newBuilder()
            .setAmountMinor(minor.longValueExact())
            .setCurrency("IDR")
            .build();
    }

    private static Timestamp toTimestamp(Instant moment) {
        return Timestamp.newBuilder()
            .setSeconds(moment.getEpochSecond())
            .setNanos(moment.getNano())
            .build();
    }

    private static Payment.EscrowStatus toStatus(String status) {
        return switch (status) {
            case "HELD" -> Payment.EscrowStatus.ESCROW_STATUS_HELD;
            case "RELEASED" -> Payment.EscrowStatus.ESCROW_STATUS_RELEASED;
            case "REFUNDED" -> Payment.EscrowStatus.ESCROW_STATUS_REFUNDED;
            case "EXPIRED" -> Payment.EscrowStatus.ESCROW_STATUS_EXPIRED;
            case "FAILED" -> Payment.EscrowStatus.ESCROW_STATUS_FAILED;
            default -> Payment.EscrowStatus.ESCROW_STATUS_UNSPECIFIED;
        };
    }
}
