package com.kinetix.payment.infrastructure.grpc;

import com.kinetix.payment.application.EscrowOutcome;
import com.kinetix.payment.application.EscrowService;
import com.kinetix.payment.domain.entity.EscrowHold;
import com.kinetix.payment.domain.exception.DomainException;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import payment.v1.Payment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentGrpcServerServiceTest {
    private static final String ORDER = "ORD-2001";
    private static final String CUSTOMER = "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7";
    private static final String MERCHANT = "3c9a77b1-58de-4a01-8f2e-6d4b19c0a8f3";
    private static final String DRIVER = "b7e2c05f-9a34-4c88-b1d6-0e7a3f52d914";

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
            42L, ORDER, CUSTOMER, MERCHANT, driver,
            new BigDecimal("150000.00"), new BigDecimal("130000.00"), new BigDecimal("20000.00"),
            EscrowHold.EscrowStatus.HELD, Instant.now().plusSeconds(3600), Instant.now(),
            null, settledAt
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
