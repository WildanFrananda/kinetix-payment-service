package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.gateway.GatewayCharge;
import com.kinetix.payment.domain.gateway.GatewayChargeRequest;
import com.kinetix.payment.domain.gateway.GatewayNotification;
import com.kinetix.payment.domain.gateway.GatewayStatus;
import com.kinetix.payment.domain.gateway.PaymentInstructions;
import com.kinetix.payment.domain.port.PaymentGatewayPort;
import java.util.function.Function;

final class ScriptedGateway implements PaymentGatewayPort {
    private final Function<GatewayChargeRequest, GatewayCharge> charges;

    private final Function<String, GatewayStatus> statuses;

    ScriptedGateway(Function<GatewayChargeRequest, GatewayCharge> charges, Function<String, GatewayStatus> statuses) {
        this.charges = charges;
        this.statuses = statuses;
    }

    @Override
    public GatewayCharge charge(GatewayChargeRequest request) {
        return charges.apply(request);
    }

    @Override
    public GatewayStatus statusOf(String referenceNumber) {
        return statuses.apply(referenceNumber);
    }

    @Override
    public PaymentInstructions instructionsFrom(String gatewayResponse) {
        return PaymentInstructions.none();
    }

    @Override
    public boolean isAuthentic(GatewayNotification notification) {
        return true;
    }
}
