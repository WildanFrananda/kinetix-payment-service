package com.kinetix.payment.api.controller;

import com.kinetix.payment.api.dto.TopUpRequest;
import com.kinetix.payment.api.dto.WalletResponse;
import com.kinetix.payment.api.security.AccessClaims;
import com.kinetix.payment.api.security.ForbiddenException;
import com.kinetix.payment.application.WalletService;
import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.DriverWallet;
import com.kinetix.payment.domain.entity.MerchantWallet;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payment/wallet")
public class WalletController {
    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping("/customer/balance")
    public ResponseEntity<WalletResponse> getCustomerBalance(@AuthenticationPrincipal Jwt jwt) {
        AccessClaims caller = require(jwt, AccessClaims.CUSTOMER);
        CustomerWallet wallet = walletService.getCustomerWallet(caller.principalId());
        return ResponseEntity.ok(WalletResponse.fromCustomer(wallet));
    }

    @PostMapping("/customer/topup")
    @ResponseStatus(HttpStatus.CREATED)
    public WalletResponse topUpCustomer(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody TopUpRequest request
    ) {
        AccessClaims caller = require(jwt, AccessClaims.CUSTOMER);
        CustomerWallet wallet = walletService.topUpCustomerWallet(caller.principalId(), request.amount());
        return WalletResponse.fromCustomer(wallet);
    }

    @GetMapping("/merchant/balance")
    public ResponseEntity<WalletResponse> getMerchantBalance(@AuthenticationPrincipal Jwt jwt) {
        AccessClaims caller = require(jwt, AccessClaims.SELLER);
        MerchantWallet wallet = walletService.getMerchantWallet(caller.principalId());
        return ResponseEntity.ok(WalletResponse.fromMerchant(wallet));
    }

    @GetMapping("/driver/balance")
    public ResponseEntity<WalletResponse> getDriverBalance(@AuthenticationPrincipal Jwt jwt) {
        AccessClaims caller = require(jwt, AccessClaims.COURIER);
        DriverWallet wallet = walletService.getDriverWallet(caller.principalId());
        return ResponseEntity.ok(WalletResponse.fromDriver(wallet));
    }

    private AccessClaims require(Jwt jwt, String role) {
        AccessClaims caller = AccessClaims.of(jwt);
        if (!caller.mayActOn(role, caller.principalId())) {
            throw new ForbiddenException(
                "this account is a " + caller.role() + " and holds no " + role + " wallet"
            );
        }
        return caller;
    }
}
