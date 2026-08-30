package com.kinetix.payment.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record TopUpRequest(
    @NotNull(message = "Amount is required")
    @DecimalMin(value = "1000.00", message = "Minimum top-up amount is IDR 1,000")
    BigDecimal amount,

    String paymentMethod
) {}
