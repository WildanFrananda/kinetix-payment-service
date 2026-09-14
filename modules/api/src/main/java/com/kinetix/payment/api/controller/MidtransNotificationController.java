package com.kinetix.payment.api.controller;

import com.kinetix.payment.api.dto.MidtransNotificationRequest;
import com.kinetix.payment.application.SettlementOutcome;
import com.kinetix.payment.application.TopUpService;
import com.kinetix.payment.domain.exception.NotificationRejectedException;
import com.kinetix.payment.domain.gateway.GatewayNotification;
import com.kinetix.payment.domain.port.PaymentGatewayPort;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payment/gateway/midtrans")
public class MidtransNotificationController {
    private static final Logger LOG = LoggerFactory.getLogger(MidtransNotificationController.class);

    private final PaymentGatewayPort gateway;
    private final TopUpService topUpService;

    public MidtransNotificationController(PaymentGatewayPort gateway, TopUpService topUpService) {
        this.gateway = gateway;
        this.topUpService = topUpService;
    }

    @PostMapping("/notifications")
    public Map<String, String> receive(@RequestBody MidtransNotificationRequest notification) {
        boolean authentic = gateway.isAuthentic(new GatewayNotification(
            notification.orderId(),
            notification.statusCode(),
            notification.grossAmount(),
            notification.signatureKey()
        ));
        if (!authentic) {
            LOG.warn("rejected a payment notification for {} whose signature does not verify",
                notification.orderId()
            );
            throw new NotificationRejectedException("the notification signature does not verify");
        }

        SettlementOutcome outcome = topUpService.settle(notification.orderId());
        return Map.of(
            "referenceNumber", outcome.transaction().referenceNumber(),
            "status", outcome.transaction().status().name()
        );
    }
}
