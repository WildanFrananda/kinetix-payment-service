package com.kinetix.payment.api.controller;

import com.kinetix.payment.api.dto.CheckoutPaymentRequest;
import com.kinetix.payment.api.dto.EscrowResponse;
import com.kinetix.payment.api.security.AccessClaims;
import com.kinetix.payment.api.security.ForbiddenException;
import com.kinetix.payment.application.EscrowService;
import com.kinetix.payment.domain.entity.EscrowHold;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payment/checkout")
public class PaymentController {
    private final EscrowService escrowService;

    public PaymentController(EscrowService escrowService) {
        this.escrowService = escrowService;
    }

    @PostMapping("/pay")
    public ResponseEntity<EscrowResponse> processCheckoutPay(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody CheckoutPaymentRequest request
    ) {
        AccessClaims caller = AccessClaims.of(jwt);
        if (!AccessClaims.CUSTOMER.equals(caller.role()) && !AccessClaims.ADMIN.equals(caller.role())) {
            throw new ForbiddenException("only a customer account can pay for an order");
        }

        EscrowHold hold = escrowService.createEscrowHold(
            request.orderNumber(),
            caller.userId(),
            request.merchantId(),
            request.driverId(),
            request.totalOrderAmount(),
            request.merchantAmount(),
            request.shippingFeeAmount()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(EscrowResponse.from(hold));
    }
}
