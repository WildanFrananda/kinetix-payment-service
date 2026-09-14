package com.kinetix.payment.api.controller;

import com.kinetix.payment.api.config.GlobalExceptionHandler;
import com.kinetix.payment.application.SettlementOutcome;
import com.kinetix.payment.application.TopUpService;
import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.entity.PaymentTransaction.PaymentMethod;
import com.kinetix.payment.domain.entity.PaymentTransaction.TransactionStatus;
import com.kinetix.payment.domain.exception.GatewayUnavailableException;
import com.kinetix.payment.domain.exception.TopUpNotFoundException;
import com.kinetix.payment.domain.gateway.GatewayNotification;
import com.kinetix.payment.domain.port.PaymentGatewayPort;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MidtransNotificationControllerTest {
    // Shaped like a real Midtrans notification, including fields this service does not read.
    private static final String NOTIFICATION = """
        {"transaction_time":"2026-09-14 10:00:00","transaction_status":"settlement",
         "transaction_id":"be03df7d","status_message":"midtrans payment notification",
         "status_code":"200","signature_key":"abc","payment_type":"bank_transfer",
         "order_id":"TOPUP-1","merchant_id":"G000000","gross_amount":"50000.00",
         "fraud_status":"accept","currency":"IDR"}
        """;

    private PaymentGatewayPort gateway;
    private TopUpService topUpService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        gateway = mock(PaymentGatewayPort.class);
        topUpService = mock(TopUpService.class);
        mvc = MockMvcBuilders.standaloneSetup(new MidtransNotificationController(gateway, topUpService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void aNotificationWhoseSignatureDoesNotVerifyMovesNothing() throws Exception {
        when(gateway.isAuthentic(any())).thenReturn(false);

        mvc.perform(notification()).andExpect(status().isUnauthorized());

        verifyNoInteractions(topUpService);
    }

    @Test
    void anAuthenticNotificationOnlyTriggersSettlementAndIsCheckedAgainstTheSignedFieldsVerbatim() throws Exception {
        when(gateway.isAuthentic(any())).thenReturn(true);
        PaymentTransaction settled = PaymentTransaction
            .pendingTopUp("TOPUP-1", "customer", "k", PaymentMethod.MIDTRANS_VA, new BigDecimal("50000"))
            .concludedAs(TransactionStatus.SUCCESS, "be03df7d", "{}");
        when(topUpService.settle("TOPUP-1")).thenReturn(new SettlementOutcome(settled, true));

        mvc.perform(notification())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"));

        ArgumentCaptor<GatewayNotification> checked = ArgumentCaptor.forClass(GatewayNotification.class);
        verify(gateway).isAuthentic(checked.capture());
        assertEquals("TOPUP-1", checked.getValue().orderId());
        assertEquals("200", checked.getValue().statusCode());
        assertEquals("50000.00", checked.getValue().grossAmount());
        verify(topUpService).settle("TOPUP-1");
    }

    @Test
    void aNotificationForATopUpNeverRecordedIs404() throws Exception {
        when(gateway.isAuthentic(any())).thenReturn(true);
        when(topUpService.settle("TOPUP-1")).thenThrow(new TopUpNotFoundException("no such top-up"));

        mvc.perform(notification()).andExpect(status().isNotFound());
    }

    @Test
    void aGatewayThatCannotConfirmIs503SoMidtransTriesAgain() throws Exception {
        when(gateway.isAuthentic(any())).thenReturn(true);
        when(topUpService.settle("TOPUP-1")).thenThrow(new GatewayUnavailableException("TOPUP-1", "cannot ask"));

        mvc.perform(notification()).andExpect(status().isServiceUnavailable());
    }

    private static MockHttpServletRequestBuilder notification() {
        return post("/api/v1/payment/gateway/midtrans/notifications")
            .contentType(MediaType.APPLICATION_JSON)
            .content(NOTIFICATION);
    }
}
