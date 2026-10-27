package com.kinetix.payment.infrastructure.grpc;

import com.google.protobuf.Timestamp;
import com.kinetix.payment.application.CreateEscrowHoldCommand;
import com.kinetix.payment.application.EscrowOutcome;
import com.kinetix.payment.application.EscrowRequestFingerprint;
import com.kinetix.payment.application.EscrowService;
import com.kinetix.payment.domain.entity.EscrowHold;
import com.kinetix.payment.domain.exception.DomainException;
import com.kinetix.payment.domain.exception.EscrowNotFoundException;
import com.kinetix.payment.domain.exception.IdempotencyConflictException;
import com.kinetix.payment.domain.exception.InsufficientBalanceException;
import com.kinetix.payment.domain.exception.InvalidArgumentException;
import common.v1.Common;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import org.springframework.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.persistence.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import payment.v1.Payment;
import payment.v1.PaymentServiceGrpc;

@GrpcService
public class PaymentGrpcServerService extends PaymentServiceGrpc.PaymentServiceImplBase {
    private static final Logger LOG = LoggerFactory.getLogger(PaymentGrpcServerService.class);

    private static final BigDecimal MINOR_PER_MAJOR = new BigDecimal("100");

    private static final String LEDGER_FAILED = "the escrow ledger could not complete this call; retry it";

    private final EscrowService escrowService;

    public PaymentGrpcServerService(EscrowService escrowService) {
        this.escrowService = escrowService;
    }

    @Override
    public void createEscrowHold(
        Payment.CreateEscrowHoldRequest request,
        StreamObserver<Payment.EscrowHoldResponse> responseObserver
    ) {
        Payment.EscrowHoldResponse response;
        try {
            response = toResponse(
                escrowService.createEscrowHold(
                    new CreateEscrowHoldCommand(
                        request.getOrderNumber(),
                        request.getCustomerPrincipalId(),
                        request.getMerchantPrincipalId(),
                        request.getDriverPrincipalId().isBlank() ? null : request.getDriverPrincipalId(),
                        fromMoney(request.getTotalOrderAmount()),
                        fromMoney(request.getMerchantAmount()),
                        fromMoney(request.getShippingFeeAmount()),
                        idempotencyKeyOf(request.hasIdempotencyKey() ? request.getIdempotencyKey() : null),
                        EscrowRequestFingerprint.forCreateHold(
                            request.getOrderNumber(),
                            request.getCustomerPrincipalId(),
                            request.getMerchantPrincipalId(),
                            request.getDriverPrincipalId(),
                            request.getTotalOrderAmount().getAmountMinor(),
                            request.getMerchantAmount().getAmountMinor(),
                            request.getShippingFeeAmount().getAmountMinor(),
                            request.getTotalOrderAmount().getCurrency()
                        )
                    )
                )
            );
        } catch (RuntimeException failure) {
            responseObserver.onError(toStatusException(failure));
            return;
        }

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void releaseEscrow(
        Payment.ReleaseEscrowRequest request,
        StreamObserver<Payment.EscrowHoldResponse> responseObserver
    ) {
        Payment.EscrowHoldResponse response;
        try {
            response = toResponse(
                escrowService.releaseEscrow(
                    request.getOrderNumber(),
                    idempotencyKeyOf(request.hasIdempotencyKey() ? request.getIdempotencyKey() : null)
                )
            );
        } catch (RuntimeException failure) {
            responseObserver.onError(toStatusException(failure));
            return;
        }

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void refundEscrow(
        Payment.RefundEscrowRequest request,
        StreamObserver<Payment.EscrowHoldResponse> responseObserver
    ) {
        Payment.EscrowHoldResponse response;
        try {
            response = toResponse(
                escrowService.refundEscrow(
                    request.getOrderNumber(),
                    request.getReason(),
                    idempotencyKeyOf(request.hasIdempotencyKey() ? request.getIdempotencyKey() : null)
                )
            );
        } catch (RuntimeException failure) {
            responseObserver.onError(toStatusException(failure));
            return;
        }

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void refundGoods(
        Payment.RefundGoodsRequest request,
        StreamObserver<Payment.EscrowHoldResponse> responseObserver
    ) {
        Payment.EscrowHoldResponse response;
        try {
            response = toResponse(
                escrowService.refundGoods(
                    request.getOrderNumber(),
                    goodsRefundOf(request),
                    request.getReason(),
                    idempotencyKeyOf(request.hasIdempotencyKey() ? request.getIdempotencyKey() : null)
                )
            );
        } catch (RuntimeException failure) {
            responseObserver.onError(toStatusException(failure));
            return;
        }

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void settleShippingFee(
        Payment.SettleShippingFeeRequest request,
        StreamObserver<Payment.EscrowHoldResponse> responseObserver
    ) {
        Payment.EscrowHoldResponse response;
        try {
            response = toResponse(
                escrowService.settleShippingFee(
                    request.getOrderNumber(),
                    request.getDriverPrincipalId()
                )
            );
        } catch (RuntimeException failure) {
            responseObserver.onError(toStatusException(failure));
            return;
        }

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void getEscrowStatus(
        Payment.GetEscrowStatusRequest request,
        StreamObserver<Payment.EscrowHoldResponse> responseObserver
    ) {
        Payment.EscrowHoldResponse response;
        try {
            response = toResponse(
                new EscrowOutcome(
                    escrowService.findByOrderNumber(request.getOrderNumber()),
                    false
                )
            );
        } catch (RuntimeException failure) {
            responseObserver.onError(toStatusException(failure));
            return;
        }

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    private static String idempotencyKeyOf(Common.IdempotencyKey key) {
        return key == null ? null : key.getKey();
    }

    private Payment.EscrowHoldResponse toResponse(EscrowOutcome outcome) {
        EscrowHold hold = outcome.hold();
        if (hold == null) {
            return Payment.EscrowHoldResponse.newBuilder()
                .setFound(false)
                .setAlreadyApplied(outcome.alreadyApplied())
                .build();
        }

        Payment.EscrowHoldResponse.Builder builder =
            Payment.EscrowHoldResponse.newBuilder()
                .setFound(true)
                .setEscrowId(hold.id() == null ? "" : hold.id().toString())
                .setOrderNumber(hold.orderNumber())
                .setCustomerPrincipalId(hold.customerPrincipalId())
                .setMerchantPrincipalId(hold.merchantPrincipalId())
                .setDriverPrincipalId(hold.driverPrincipalId() == null ? "" : hold.driverPrincipalId())
                .setTotalOrderAmount(toMoney(hold.totalOrderAmount()))
                .setMerchantAmount(toMoney(hold.merchantAmount()))
                .setShippingFeeAmount(toMoney(hold.shippingFeeAmount()))
                .setStatus(toStatus(hold.status()))
                .setAlreadyApplied(outcome.alreadyApplied());

        builder.setGoodsRefundedAmount(toMoney(hold.goodsRefundedAmount()));
        if (hold.createdAt() != null) {
            builder.setCreatedAt(toTimestamp(hold.createdAt()));
        }

        if (hold.releasedAt() != null) {
            builder.setReleasedAt(toTimestamp(hold.releasedAt()));
        }

        if (hold.shippingFeeSettledAt() != null) {
            builder.setShippingFeeSettledAt(toTimestamp(hold.shippingFeeSettledAt()));
        }

        return builder.build();
    }

    private static StatusRuntimeException toStatusException(RuntimeException failure) {
        if (failure instanceof IdempotencyConflictException conflict) {
            return GrpcRefusal.of(Status.INVALID_ARGUMENT, "IDEMPOTENCY_KEY_REUSED", conflict.getMessage());
        }

        if (failure instanceof InvalidArgumentException invalid) {
            return GrpcRefusal.of(Status.INVALID_ARGUMENT, "INVALID_ARGUMENT", invalid.getMessage());
        }

        if (failure instanceof IllegalArgumentException invalid) {
            LOG.warn("escrow call refused by an argument check payment did not write", invalid);
            return GrpcRefusal.of(Status.INVALID_ARGUMENT, "INVALID_ARGUMENT", GrpcRefusal.INVALID_REQUEST);
        }

        if (failure instanceof EscrowNotFoundException notFound) {
            return GrpcRefusal.of(Status.NOT_FOUND, "ESCROW_NOT_FOUND", notFound.getMessage());
        }

        if (failure instanceof InsufficientBalanceException insufficient) {
            return GrpcRefusal.of(Status.FAILED_PRECONDITION, "INSUFFICIENT_BALANCE", insufficient.getMessage());
        }

        if (failure instanceof DomainException refused) {
            return GrpcRefusal.of(Status.FAILED_PRECONDITION, "ESCROW_REFUSED", refused.getMessage());
        }

        if (failure instanceof DataAccessException
            || failure instanceof TransactionException
            || failure instanceof PersistenceException
        ) {
            LOG.warn("escrow call aborted on contention", failure);
            return GrpcRefusal.of(Status.ABORTED, "ESCROW_CONTENTION", LEDGER_FAILED);
        }

        LOG.error("escrow call failed", failure);
        return Status.INTERNAL
            .withDescription("the escrow call could not be completed")
            .asRuntimeException();
    }

    private static BigDecimal goodsRefundOf(Payment.RefundGoodsRequest request) {
        if (!request.hasAmount()) {
            throw new InvalidArgumentException("a goods refund names the amount to refund");
        }

        if (!"IDR".equals(request.getAmount().getCurrency())) {
            throw new InvalidArgumentException(
                "a goods refund is in IDR, not '" + request.getAmount().getCurrency() + "'"
            );
        }

        return fromMoney(request.getAmount());
    }

    private static BigDecimal fromMoney(Common.Money money) {
        if (money == null) {
            return BigDecimal.ZERO;
        }

        return BigDecimal.valueOf(money.getAmountMinor())
            .divide(MINOR_PER_MAJOR);
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

    private static Payment.EscrowStatus toStatus(EscrowHold.EscrowStatus status) {
        return switch (status) {
            case HELD -> Payment.EscrowStatus.ESCROW_STATUS_HELD;
            case RELEASED -> Payment.EscrowStatus.ESCROW_STATUS_RELEASED;
            case REFUNDED -> Payment.EscrowStatus.ESCROW_STATUS_REFUNDED;
            case DISPUTED -> Payment.EscrowStatus.ESCROW_STATUS_UNSPECIFIED;
        };
    }
}
