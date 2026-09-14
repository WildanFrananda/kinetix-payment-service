package com.kinetix.payment.domain.port;

import com.kinetix.payment.domain.gateway.GatewayCharge;
import com.kinetix.payment.domain.gateway.GatewayChargeRequest;
import com.kinetix.payment.domain.gateway.GatewayNotification;
import com.kinetix.payment.domain.gateway.GatewayStatus;
import com.kinetix.payment.domain.gateway.PaymentInstructions;

public interface PaymentGatewayPort {
    GatewayCharge charge(GatewayChargeRequest request);

    GatewayStatus statusOf(String referenceNumber);

    PaymentInstructions instructionsFrom(String gatewayResponse);

    boolean isAuthentic(GatewayNotification notification);
}
