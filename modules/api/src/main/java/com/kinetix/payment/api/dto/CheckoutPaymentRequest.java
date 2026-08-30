package com.kinetix.payment.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CheckoutPaymentRequest(
    @NotBlank(message = "Order number is required")
    String orderNumber,

    @NotNull(message = "Merchant ID is required")
    Long merchantId,

    Long driverId,

    @NotNull(message = "Total order amount is required")
    @DecimalMin(value = "1.00", message = "Total order amount must be greater than 0")
    BigDecimal totalOrderAmount,

    @NotNull(message = "Merchant amount is required")
    BigDecimal merchantAmount,

    @NotNull(message = "Shipping fee amount is required")
    BigDecimal shippingFeeAmount
) {}
