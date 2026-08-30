package com.kinetix.payment.api.controller;

import com.kinetix.payment.api.dto.TopUpRequest;
import com.kinetix.payment.api.dto.WalletResponse;
import com.kinetix.payment.application.WalletService;
import com.kinetix.payment.domain.entity.CustomerWallet;
import com.kinetix.payment.domain.entity.DriverWallet;
import com.kinetix.payment.domain.entity.MerchantWallet;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payment/wallet")
public class WalletController {
    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping("/customer/balance")
    public ResponseEntity<WalletResponse> getCustomerBalance(@RequestHeader("X-User-Id") Long customerId) {
        CustomerWallet wallet = walletService.getCustomerWallet(customerId);
        return ResponseEntity.ok(WalletResponse.fromCustomer(wallet));
    }

    @PostMapping("/customer/topup")
    public ResponseEntity<WalletResponse> topUpCustomer(
        @RequestHeader("X-User-Id") Long customerId,
        @Valid @RequestBody TopUpRequest request
    ) {
        CustomerWallet wallet = walletService.topUpCustomerWallet(customerId, request.amount());
        return ResponseEntity.status(HttpStatus.CREATED).body(WalletResponse.fromCustomer(wallet));
    }

    @GetMapping("/merchant/balance")
    public ResponseEntity<WalletResponse> getMerchantBalance(@RequestHeader("X-User-Id") Long merchantId) {
        MerchantWallet wallet = walletService.getMerchantWallet(merchantId);
        return ResponseEntity.ok(WalletResponse.fromMerchant(wallet));
    }

    @GetMapping("/driver/balance")
    public ResponseEntity<WalletResponse> getDriverBalance(@RequestHeader("X-User-Id") Long driverId) {
        DriverWallet wallet = walletService.getDriverWallet(driverId);
        return ResponseEntity.ok(WalletResponse.fromDriver(wallet));
    }
}
