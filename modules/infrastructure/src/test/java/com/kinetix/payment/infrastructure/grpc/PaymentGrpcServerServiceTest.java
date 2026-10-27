package com.kinetix.payment.infrastructure.grpc;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.kinetix.payment.application.EscrowOutcome;
import com.kinetix.payment.application.EscrowService;
import com.kinetix.payment.domain.entity.EscrowHold;
import com.kinetix.payment.domain.exception.DomainException;
import common.v1.Common;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.StreamObserver;
import jakarta.persistence.PersistenceException;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionSystemException;
import payment.v1.Payment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentGrpcServerServiceTest {
    private static final String ORDER = "ORD-2001";
    private static final String CUSTOMER = "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7";
    private static final String MERCHANT = "3c9a77b1-58de-4a01-8f2e-6d4b19c0a8f3";
    private static final String DRIVER = "b7e2c05f-9a34-4c88-b1d6-0e7a3f52d914";
    private static final String SQL_TEXT =
        "could not execute statement [ERROR: deadlock detected Detail: Process 4711 waits for ShareLock on "
            + "transaction 99] [update customer_wallets set balance=? where id=?]; SQL [update customer_wallets "
            + "set balance=? where id=?]";
    private static final String LEDGER_FAILED = "the escrow ledger could not complete this call; retry it";
    private static final Metadata.Key<Common.ErrorDetail> ERROR_DETAIL =
        ProtoUtils.keyForProto(Common.ErrorDetail.getDefaultInstance());

    private EscrowService escrowService;
    private PaymentGrpcServerService server;
    private CapturingObserver observer;

    @BeforeEach
    void setUp() {
        escrowService = mock(EscrowService.class);
        server = new PaymentGrpcServerService(escrowService);
        observer = new CapturingObserver();
    }

    private static EscrowHold hold(Instant settledAt, String driver) {
        return new EscrowHold(
            42L,
            ORDER,
            CUSTOMER,
            MERCHANT,
            driver,
            new BigDecimal("150000.00"),
            new BigDecimal("130000.00"),
            new BigDecimal("20000.00"),
            EscrowHold.EscrowStatus.HELD,
            Instant.now(),
            null,
            settledAt,
            BigDecimal.ZERO
        );
    }

    private static Payment.SettleShippingFeeRequest request(String driver) {
        return Payment.SettleShippingFeeRequest.newBuilder()
            .setOrderNumber(ORDER)
            .setDriverPrincipalId(driver)
            .build();
    }

    @Test
    void settleShippingFeePassesTheOrderAndDriverThrough() {
        when(escrowService.settleShippingFee(any(), any()))
            .thenReturn(new EscrowOutcome(hold(Instant.now(), DRIVER), false));

        server.settleShippingFee(request(DRIVER), observer);

        verify(escrowService).settleShippingFee(ORDER, DRIVER);
        assertTrue(observer.completed);
        assertEquals(DRIVER, observer.response.getDriverPrincipalId());
    }

    @Test
    void theAnswerCarriesWhenTheFeeWasSettled() {
        Instant settledAt = Instant.parse("2026-09-17T10:15:30Z");
        when(escrowService.settleShippingFee(any(), any()))
            .thenReturn(new EscrowOutcome(hold(settledAt, DRIVER), false));

        server.settleShippingFee(request(DRIVER), observer);

        assertTrue(observer.response.hasShippingFeeSettledAt());
        assertEquals(settledAt.getEpochSecond(), observer.response.getShippingFeeSettledAt().getSeconds());
    }

    @Test
    void anUnsettledHoldCarriesNoSettlementTimestamp() {
        when(escrowService.settleShippingFee(any(), any()))
            .thenReturn(new EscrowOutcome(hold(null, null), false));

        server.settleShippingFee(request(DRIVER), observer);

        assertFalse(observer.response.hasShippingFeeSettledAt());
        assertEquals("", observer.response.getDriverPrincipalId());
    }

    @Test
    void aRepeatIsReportedAsAlreadyApplied() {
        when(escrowService.settleShippingFee(any(), any()))
            .thenReturn(new EscrowOutcome(hold(Instant.now(), DRIVER), true));

        server.settleShippingFee(request(DRIVER), observer);

        assertTrue(observer.response.getAlreadyApplied());
    }

    @Test
    void aRefusalBecomesAnError() {
        when(escrowService.settleShippingFee(any(), any()))
            .thenThrow(new DomainException("already settled to somebody else"));

        server.settleShippingFee(request(DRIVER), observer);

        assertNull(observer.response);
        assertFalse(observer.completed);
        assertInstanceOf(StatusRuntimeException.class, observer.error);
    }

    @Test
    void aLedgerFailureIsAbortedWithAFixedDescriptionAndNeverTheDatabaseText() {
        List<RuntimeException> failures = List.of(
            new DataIntegrityViolationException(SQL_TEXT),
            new CannotAcquireLockException(SQL_TEXT),
            new TransactionSystemException(SQL_TEXT),
            new PersistenceException(SQL_TEXT)
        );
        for (RuntimeException failure : failures) {
            CapturingObserver answered = new CapturingObserver();
            doThrow(failure).when(escrowService).settleShippingFee(any(), any());

            server.settleShippingFee(request(DRIVER), answered);

            StatusRuntimeException refused = assertInstanceOf(StatusRuntimeException.class, answered.error);
            String seen = failure.getClass().getSimpleName() + " answered " + refused.getStatus();
            assertEquals(Status.Code.ABORTED, refused.getStatus().getCode(), seen);
            assertEquals(LEDGER_FAILED, refused.getStatus().getDescription(), seen);
            assertEquals("ESCROW_CONTENTION", refused.getTrailers().get(ERROR_DETAIL).getErrorCode(), seen);
            assertEquals(LEDGER_FAILED, refused.getTrailers().get(ERROR_DETAIL).getMessage(), seen);
        }
    }

    @Test
    void aLedgerFailureIsLoggedWithItsExceptionUnderTheCallersRequestId() {
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        Logger logger = (Logger) LoggerFactory.getLogger(PaymentGrpcServerService.class);
        captured.start();
        logger.addAppender(captured);
        when(escrowService.settleShippingFee(any(), any()))
            .thenThrow(new DataIntegrityViolationException(SQL_TEXT));
        MDC.put("requestId", "req-escrow-1");
        try {
            server.settleShippingFee(request(DRIVER), observer);
        } finally {
            MDC.remove("requestId");
            logger.detachAppender(captured);
        }

        ILoggingEvent logged = captured.list.getFirst();
        assertEquals("req-escrow-1", logged.getMDCPropertyMap().get("requestId"));
        assertEquals(DataIntegrityViolationException.class.getName(), logged.getThrowableProxy().getClassName());
        assertEquals(SQL_TEXT, logged.getThrowableProxy().getMessage());
    }

    @Test
    void anArgumentCheckPaymentDidNotWriteIsRefusedWithoutItsText() {
        when(escrowService.settleShippingFee(any(), any())).thenThrow(
            new IllegalArgumentException(
                "No enum constant com.kinetix.payment.domain.entity.EscrowHold.EscrowStatus.SHIPPED"
            )
        );

        server.settleShippingFee(request(DRIVER), observer);

        StatusRuntimeException refused = assertInstanceOf(StatusRuntimeException.class, observer.error);
        assertEquals(Status.Code.INVALID_ARGUMENT, refused.getStatus().getCode());
        assertEquals("the request is not valid", refused.getStatus().getDescription());
        assertEquals("INVALID_ARGUMENT", refused.getTrailers().get(ERROR_DETAIL).getErrorCode());
        assertEquals("the request is not valid", refused.getTrailers().get(ERROR_DETAIL).getMessage());
    }

    @Test
    void paymentsOwnRefusalStillSaysWhy() {
        when(escrowService.settleShippingFee(any(), any()))
            .thenThrow(new DomainException("escrow for order ORD-2001 is RELEASED"));

        server.settleShippingFee(request(DRIVER), observer);

        StatusRuntimeException refused = assertInstanceOf(StatusRuntimeException.class, observer.error);
        assertEquals(Status.Code.FAILED_PRECONDITION, refused.getStatus().getCode());
        assertEquals("escrow for order ORD-2001 is RELEASED", refused.getStatus().getDescription());
        assertEquals("ESCROW_REFUSED", refused.getTrailers().get(ERROR_DETAIL).getErrorCode());
        assertEquals("escrow for order ORD-2001 is RELEASED", refused.getTrailers().get(ERROR_DETAIL).getMessage());
    }

    private static Payment.RefundGoodsRequest goodsRefund(long minor, String currency) {
        return Payment.RefundGoodsRequest.newBuilder()
            .setOrderNumber(ORDER)
            .setAmount(common.v1.Common.Money.newBuilder().setAmountMinor(minor).setCurrency(currency))
            .setReason("returned")
            .build();
    }

    @Test
    void aGoodsRefundIsPassedThroughInRupiahAndAnswersWithWhatHasBeenRefunded() {
        when(escrowService.refundGoods(any(), any(), any(), any()))
            .thenReturn(new EscrowOutcome(hold(Instant.now(), DRIVER).refundGoods(new BigDecimal("40000.00")), false));

        server.refundGoods(goodsRefund(4_000_000L, "IDR"), observer);

        verify(
            escrowService
        ).refundGoods(
            eq(ORDER),
            argThat(amount -> new BigDecimal("40000").compareTo(amount) == 0),
            eq("returned"),
            any()
        );
        assertTrue(observer.completed);
        assertEquals(4_000_000L, observer.response.getGoodsRefundedAmount().getAmountMinor());
    }

    @Test
    void aGoodsRefundInAnotherCurrencyIsRefusedRatherThanReadAsRupiah() {
        server.refundGoods(goodsRefund(4_000_000L, "USD"), observer);

        StatusRuntimeException refused = assertInstanceOf(StatusRuntimeException.class, observer.error);
        assertEquals(Status.Code.INVALID_ARGUMENT, refused.getStatus().getCode());
        assertEquals("a goods refund is in IDR, not 'USD'", refused.getStatus().getDescription());
        verifyNoInteractions(escrowService);
    }

    @Test
    void aGoodsRefundThatNamesNoAmountIsRefusedRatherThanReadAsZero() {
        server.refundGoods(Payment.RefundGoodsRequest.newBuilder().setOrderNumber(ORDER).build(), observer);

        assertEquals(
            io.grpc.Status.Code.INVALID_ARGUMENT,
            ((StatusRuntimeException) observer.error).getStatus().getCode()
        );
        verifyNoInteractions(escrowService);
    }

    private static final class CapturingObserver implements StreamObserver<Payment.EscrowHoldResponse> {
        private Payment.EscrowHoldResponse response;
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(Payment.EscrowHoldResponse value) {
            this.response = value;
        }

        @Override
        public void onError(Throwable throwable) {
            this.error = throwable;
        }

        @Override
        public void onCompleted() {
            this.completed = true;
        }
    }
}
