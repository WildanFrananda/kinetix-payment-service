package com.kinetix.payment.api.controller;

import com.kinetix.payment.api.config.GlobalExceptionHandler;
import com.kinetix.payment.application.TopUpCommand;
import com.kinetix.payment.application.TopUpOutcome;
import com.kinetix.payment.application.TopUpService;
import com.kinetix.payment.application.WalletService;
import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.entity.PaymentTransaction.PaymentMethod;
import com.kinetix.payment.domain.exception.GatewayRefusedException;
import com.kinetix.payment.domain.exception.GatewayUnavailableException;
import com.kinetix.payment.domain.gateway.PaymentInstructions;
import com.kinetix.payment.domain.gateway.VirtualAccountBank;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
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

class WalletControllerTopUpTest {
    private static final String CUSTOMER = "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7";
    private static final String TOP_UP = "/api/v1/payment/wallet/customer/topup";
    private static final String BODY = "{\"amount\": 50000, \"paymentMethod\": \"MIDTRANS_VA\", \"bank\": \"BCA\"}";

    private WalletService walletService;
    private TopUpService topUpService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        walletService = mock(WalletService.class);
        topUpService = mock(TopUpService.class);
        mvc = MockMvcBuilders.standaloneSetup(new WalletController(walletService, topUpService))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        signInAs("customer");
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aTopUpAnswersAcceptedWithHowToPayAndChangesNoBalance() throws Exception {
        when(topUpService.requestTopUp(any())).thenReturn(new TopUpOutcome(
            pending(), new PaymentInstructions(VirtualAccountBank.BCA, "91019021579", null, null), false
        ));

        mvc.perform(topUp("k-1"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.referenceNumber").value("TOPUP-1"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.instructions.virtualAccountNumber").value("91019021579"));

        verifyNoInteractions(walletService);
    }

    @Test
    void theWalletIsTheTokensAndTheKeyIsTheHeaders() throws Exception {
        when(topUpService.requestTopUp(any())).thenReturn(new TopUpOutcome(pending(), PaymentInstructions.none(), false));

        mvc.perform(topUp("k-2")).andExpect(status().isAccepted());

        ArgumentCaptor<TopUpCommand> command = ArgumentCaptor.forClass(TopUpCommand.class);
        verify(topUpService).requestTopUp(command.capture());
        assertEquals(CUSTOMER, command.getValue().customerPrincipalId());
        assertEquals("k-2", command.getValue().idempotencyKey());
    }

    @Test
    void aTopUpWithoutAnIdempotencyKeyChargesNothing() throws Exception {
        mvc.perform(post(TOP_UP).contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(topUpService);
    }

    @Test
    void onlyACustomerMayTopUp() throws Exception {
        signInAs("seller");

        mvc.perform(topUp("k-3")).andExpect(status().isForbidden());

        verifyNoInteractions(topUpService);
    }

    @Test
    void aGatewayThatGaveNoDefiniteAnswerIs503WithTheReferenceToAskAboutLater() throws Exception {
        when(topUpService.requestTopUp(any())).thenThrow(new GatewayUnavailableException("TOPUP-9", "no answer"));

        mvc.perform(topUp("k-4"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.referenceNumber").value("TOPUP-9"));
    }

    @Test
    void aGatewayRefusalIs502() throws Exception {
        when(topUpService.requestTopUp(any())).thenThrow(new GatewayRefusedException("TOPUP-8", "refused"));

        mvc.perform(topUp("k-5")).andExpect(status().isBadGateway());
    }

    private static MockHttpServletRequestBuilder topUp(String idempotencyKey) {
        return post(TOP_UP)
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(BODY);
    }

    private static PaymentTransaction pending() {
        return PaymentTransaction.pendingTopUp("TOPUP-1", CUSTOMER, "k-1", PaymentMethod.MIDTRANS_VA, new BigDecimal("50000"))
            .withGatewayResponse("mid-1", "{}");
    }

    private static void signInAs(String role) {
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject(CUSTOMER)
            .claim("email", "customer@kinetix.test")
            .claim("role", role)
            .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
