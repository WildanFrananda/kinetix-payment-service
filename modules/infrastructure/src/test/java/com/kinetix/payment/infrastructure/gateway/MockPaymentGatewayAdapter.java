package com.kinetix.payment.infrastructure.gateway;

import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.port.PaymentGatewayPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class MockPaymentGatewayAdapter implements PaymentGatewayPort {
    @Override
    public PaymentTransaction createTopUpTransaction(Long customerId, BigDecimal amount, PaymentTransaction.PaymentMethod method) {
        String refNum = "TOPUP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String extTxId = "MOCK-PG-" + System.currentTimeMillis();

        return new PaymentTransaction(
            null,
            refNum,
            extTxId,
            customerId,
            PaymentTransaction.TransactionType.TOPUP,
            method != null ? method : PaymentTransaction.PaymentMethod.MOCK_SANDBOX,
            amount,
            PaymentTransaction.TransactionStatus.SUCCESS,
            "{\"mock_gateway_status\": \"SETTLED\", \"provider\": \"KINETIX_SIMULATOR\"}",
            Instant.now()
        );
    }

    @Override
    public PaymentTransaction processCheckoutPayment(String orderNumber, Long customerId, BigDecimal amount, PaymentTransaction.PaymentMethod method) {
        String refNum = "PAY-" + orderNumber;
        String extTxId = "MOCK-PG-" + System.currentTimeMillis();

        return new PaymentTransaction(
            null,
            refNum,
            extTxId,
            customerId,
            PaymentTransaction.TransactionType.CHECKOUT_PAYMENT,
            method != null ? method : PaymentTransaction.PaymentMethod.INTERNAL_WALLET,
            amount,
            PaymentTransaction.TransactionStatus.SUCCESS,
            "{\"mock_gateway_status\": \"SETTLED\", \"order_number\": \"" + orderNumber + "\"}",
            Instant.now()
        );
    }
}
