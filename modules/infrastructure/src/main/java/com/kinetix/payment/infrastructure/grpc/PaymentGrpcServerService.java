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
import common.v1.Common;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import net.devh.boot.grpc.server.service.GrpcService;
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

    private static final Metadata.Key<Common.ErrorDetail> ERROR_DETAIL =
        ProtoUtils.keyForProto(Common.ErrorDetail.getDefaultInstance());

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
            response = toResponse(escrowService.createEscrowHold(new CreateEscrowHoldCommand(
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
            )));
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
            response = toResponse(escrowService.releaseEscrow(
                request.getOrderNumber(),
                idempotencyKeyOf(request.hasIdempotencyKey() ? request.getIdempotencyKey() : null)
            ));
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
            response = toResponse(escrowService.refundEscrow(
                request.getOrderNumber(),
                request.getReason(),
                idempotencyKeyOf(request.hasIdempotencyKey() ? request.getIdempotencyKey() : null)
            ));
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
            response = toResponse(new EscrowOutcome(
                escrowService.findByOrderNumber(request.getOrderNumber()), false
            ));
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

    private static StatusRuntimeException toStatusException(RuntimeException failure) {
        if (failure instanceof IdempotencyConflictException conflict) {
            return refusal(Status.INVALID_ARGUMENT, "IDEMPOTENCY_KEY_REUSED", conflict);
        }
        if (failure instanceof IllegalArgumentException invalid) {
            return refusal(Status.INVALID_ARGUMENT, "INVALID_ARGUMENT", invalid);
        }
        if (failure instanceof EscrowNotFoundException notFound) {
            return refusal(Status.NOT_FOUND, "ESCROW_NOT_FOUND", notFound);
        }
        if (failure instanceof InsufficientBalanceException insufficient) {
            return refusal(Status.FAILED_PRECONDITION, "INSUFFICIENT_BALANCE", insufficient);
        }
        if (failure instanceof DomainException refused) {
            return refusal(Status.FAILED_PRECONDITION, "ESCROW_REFUSED", refused);
        }
        if (failure instanceof DataAccessException
            || failure instanceof TransactionException
            || failure instanceof PersistenceException
        ) {
            LOG.warn("escrow call aborted on contention", failure);
            return refusal(Status.ABORTED, "ESCROW_CONTENTION", failure);
        }
        LOG.error("escrow call failed", failure);
        return Status.INTERNAL
            .withDescription("the escrow call could not be completed")
            .asRuntimeException();
    }

    private static StatusRuntimeException refusal(Status status, String errorCode, Throwable cause) {
        String message = cause.getMessage() == null ? errorCode : cause.getMessage();
        Metadata trailers = new Metadata();
        trailers.put(ERROR_DETAIL, Common.ErrorDetail.newBuilder()
            .setErrorCode(errorCode)
            .setMessage(message)
            .build()
        );
        return status.withDescription(message).asRuntimeException(trailers);
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
