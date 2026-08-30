package com.kinetix.payment.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ReleaseEscrowRequest(
    @NotBlank(message = "Order number is required")
    String orderNumber
) {}
