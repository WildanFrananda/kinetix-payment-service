package com.kinetix.payment.api.controller;

import com.kinetix.payment.api.config.GlobalExceptionHandler;
import com.kinetix.payment.application.TopUpService;
import com.kinetix.payment.application.WalletService;
import com.kinetix.payment.domain.entity.CustomerWallet;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WalletControllerBalanceTest {
    private static final String PRINCIPAL = "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7";

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
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anAdminReadingABalanceIsRefusedAndNoWalletIsLookedUpForIt() throws Exception {
        signInAs("admin");

        mvc.perform(get("/api/v1/payment/wallet/customer/balance")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/payment/wallet/merchant/balance")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/payment/wallet/driver/balance")).andExpect(status().isForbidden());

        verifyNoInteractions(walletService);
    }

    @Test
    void anAdminCannotTopUpUnderItsOwnPrincipal() throws Exception {
        signInAs("admin");

        mvc.perform(
            post("/api/v1/payment/wallet/customer/topup")
                .header("Idempotency-Key", "k-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 50000, \"paymentMethod\": \"MIDTRANS_VA\", \"bank\": \"BCA\"}")
        )
            .andExpect(status().isForbidden());

        verifyNoInteractions(topUpService);
    }

    @Test
    void aCustomerWithNoWalletReadsNothingOwedAndIsToldNoWalletExists() throws Exception {
        signInAs("customer");
        when(walletService.findCustomerWallet(PRINCIPAL)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/payment/wallet/customer/balance"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exists").value(false))
            .andExpect(jsonPath("$.id").isEmpty())
            .andExpect(jsonPath("$.ownerPrincipalId").value(PRINCIPAL))
            .andExpect(content().string(containsString("\"availableBalance\":0.00")))
            .andExpect(content().string(containsString("\"heldOrPendingBalance\":0.00")));
    }

    @Test
    void aCustomerWithAWalletReadsItsBalanceAtTheLedgerScale() throws Exception {
        signInAs("customer");
        CustomerWallet opened = new CustomerWallet(
            7L,
            PRINCIPAL,
            new BigDecimal("150000"),
            BigDecimal.ZERO,
            "IDR",
            null,
            null
        );
        when(walletService.findCustomerWallet(PRINCIPAL)).thenReturn(Optional.of(opened));

        mvc.perform(get("/api/v1/payment/wallet/customer/balance"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exists").value(true))
            .andExpect(jsonPath("$.id").value(7))
            .andExpect(content().string(containsString("\"availableBalance\":150000.00")));
    }

    private static void signInAs(String role) {
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject(PRINCIPAL)
            .claim("email", "someone@kinetix.test")
            .claim("role", role)
            .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
