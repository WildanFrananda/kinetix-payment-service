package com.kinetix.payment.api.controller;

import com.kinetix.payment.api.dto.EscrowResponse;
import com.kinetix.payment.api.dto.ReleaseEscrowRequest;
import com.kinetix.payment.application.EscrowService;
import com.kinetix.payment.domain.entity.EscrowHold;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payment/escrow")
public class EscrowController {
    private final EscrowService escrowService;

    public EscrowController(EscrowService escrowService) {
        this.escrowService = escrowService;
    }

    @PostMapping("/release")
    public ResponseEntity<EscrowResponse> releaseEscrow(@Valid @RequestBody ReleaseEscrowRequest request) {
        EscrowHold released = escrowService.releaseEscrow(request.orderNumber());
        return ResponseEntity.ok(EscrowResponse.from(released));
    }
}
