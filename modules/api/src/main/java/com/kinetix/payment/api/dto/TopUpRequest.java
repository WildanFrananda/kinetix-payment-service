package com.kinetix.payment.api.dto;

import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.gateway.VirtualAccountBank;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record TopUpRequest(
    @NotNull(message = "Amount is required")
    @DecimalMin(value = "1000", message = "Minimum top-up amount is IDR 1,000")
    BigDecimal amount,

    @NotNull(message = "A payment method is required: MIDTRANS_VA or MIDTRANS_QRIS")
    PaymentTransaction.PaymentMethod paymentMethod,

    VirtualAccountBank bank
) {}
