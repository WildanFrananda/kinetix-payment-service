package com.kinetix.payment.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SettleShippingFeeRequest(
    @NotBlank(message = "Order number is required")
    String orderNumber,

    @NotBlank(message = "The driver principal id is required: a shipping fee is owed to somebody")
    String driverPrincipalId
) {}
