package com.kinetix.payment.api.controller;

import com.kinetix.payment.api.dto.EscrowResponse;
import com.kinetix.payment.api.dto.SettleShippingFeeRequest;
import com.kinetix.payment.api.security.AccessClaims;
import com.kinetix.payment.api.security.ForbiddenException;
import com.kinetix.payment.application.EscrowService;
import com.kinetix.payment.domain.entity.EscrowHold;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payment/escrow")
public class EscrowController {
    private final EscrowService escrowService;

    public EscrowController(EscrowService escrowService) {
        this.escrowService = escrowService;
    }

    @PostMapping("/shipping-fee/settle")
    public ResponseEntity<EscrowResponse> settleShippingFee(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody SettleShippingFeeRequest request
    ) {
        AccessClaims caller = AccessClaims.of(jwt);
        if (!AccessClaims.ADMIN.equals(caller.role())) {
            throw new ForbiddenException("settling a shipping fee is an administrative action");
        }

        EscrowHold settled = escrowService
            .settleShippingFee(request.orderNumber(), request.driverPrincipalId())
            .hold();
        return ResponseEntity.ok(EscrowResponse.from(settled));
    }
}
