package com.kinetix.payment.infrastructure.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kinetix.payment.domain.entity.PaymentTransaction;
import com.kinetix.payment.domain.port.PaymentGatewayPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Component
@Primary
public class MidtransPaymentGatewayAdapter implements PaymentGatewayPort {
    private final String serverKey;
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public MidtransPaymentGatewayAdapter(
        @Value("${midtrans.server-key}") String serverKey,
        @Value("${midtrans.is-production}") boolean isProduction,
        ObjectMapper objectMapper
    ) {
        this.serverKey = serverKey;
        this.baseUrl = isProduction
            ? "https://api.midtrans.com/v2"
            : "https://api.sandbox.midtrans.com/v2";
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public PaymentTransaction createTopUpTransaction(Long customerId, BigDecimal amount, PaymentTransaction.PaymentMethod method) {
        String refNum = "TOPUP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return executeMidtransCharge(refNum, customerId, amount, PaymentTransaction.TransactionType.TOPUP, method);
    }

    @Override
    public PaymentTransaction processCheckoutPayment(String orderNumber, Long customerId, BigDecimal amount, PaymentTransaction.PaymentMethod method) {
        String refNum = "PAY-" + orderNumber;
        return executeMidtransCharge(refNum, customerId, amount, PaymentTransaction.TransactionType.CHECKOUT_PAYMENT, method);
    }

    private PaymentTransaction executeMidtransCharge(
        String referenceNumber,
        Long userId,
        BigDecimal amount,
        PaymentTransaction.TransactionType type,
        PaymentTransaction.PaymentMethod method
    ) {
        try {
            String midtransPaymentType = mapPaymentType(method);
            Map<String, Object> payload = Map.of(
                "payment_type", midtransPaymentType,
                "transaction_details", Map.of(
                    "order_id", referenceNumber,
                    "gross_amount", amount.longValue()
                ),
                "customer_details", Map.of(
                    "user_id", userId.toString(),
                    "email", "user" + userId + "@kinetix.shop"
                )
            );

            String requestBody = objectMapper.writeValueAsString(payload);
            String authHeader = "Basic " + Base64.getEncoder().encodeToString((serverKey + ":").getBytes(StandardCharsets.UTF_8));

            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/charge"))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() >= 200 && httpResponse.statusCode() < 300) {
                Map<String, Object> respMap = objectMapper.readValue(httpResponse.body(), new TypeReference<Map<String, Object>>() {});
                Object txIdObj = respMap.get("transaction_id");
                String extTxId = txIdObj != null ? txIdObj.toString() : referenceNumber;

                return new PaymentTransaction(
                    null,
                    referenceNumber,
                    extTxId,
                    userId,
                    type,
                    method,
                    amount,
                    PaymentTransaction.TransactionStatus.SUCCESS,
                    httpResponse.body(),
                    Instant.now()
                );
            } else {
                return new PaymentTransaction(
                    null,
                    referenceNumber,
                    null,
                    userId,
                    type,
                    method,
                    amount,
                    PaymentTransaction.TransactionStatus.FAILED,
                    httpResponse.body(),
                    Instant.now()
                );
            }
        } catch (Exception e) {
            return new PaymentTransaction(
                null,
                referenceNumber,
                null,
                userId,
                type,
                method,
                amount,
                PaymentTransaction.TransactionStatus.FAILED,
                "{\"error\": \"" + e.getMessage() + "\"}",
                Instant.now()
            );
        }
    }

    private String mapPaymentType(PaymentTransaction.PaymentMethod method) {
        if (method == null) {
            return "bank_transfer";
        }
        return switch (method) {
            case MIDTRANS_QRIS -> "gopay";
            case MIDTRANS_VA -> "bank_transfer";
            default -> "bank_transfer";
        };
    }
}
