package com.kinetix.payment.api.controller;

import com.kinetix.payment.api.dto.CheckoutPaymentRequest;
import com.kinetix.payment.api.dto.EscrowResponse;
import com.kinetix.payment.application.EscrowService;
import com.kinetix.payment.domain.entity.EscrowHold;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
        @RequestHeader("X-User-Id") Long customerId,
        @Valid @RequestBody CheckoutPaymentRequest request
    ) {
        EscrowHold hold = escrowService.createEscrowHold(
            request.orderNumber(),
            customerId,
            request.merchantId(),
            request.driverId(),
            request.totalOrderAmount(),
            request.merchantAmount(),
            request.shippingFeeAmount()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(EscrowResponse.from(hold));
    }
}
