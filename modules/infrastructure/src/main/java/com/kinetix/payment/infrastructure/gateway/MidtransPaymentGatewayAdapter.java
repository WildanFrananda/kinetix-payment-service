package com.kinetix.payment.infrastructure.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kinetix.payment.domain.gateway.GatewayCharge;
import com.kinetix.payment.domain.gateway.GatewayChargeOutcome;
import com.kinetix.payment.domain.gateway.GatewayChargeRequest;
import com.kinetix.payment.domain.gateway.GatewayNotification;
import com.kinetix.payment.domain.gateway.GatewaySettlement;
import com.kinetix.payment.domain.gateway.GatewayStatus;
import com.kinetix.payment.domain.gateway.PaymentInstructions;
import com.kinetix.payment.domain.gateway.VirtualAccountBank;
import com.kinetix.payment.domain.port.PaymentGatewayPort;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MidtransPaymentGatewayAdapter implements PaymentGatewayPort {
    private static final String QR_CODE_ACTION = "generate-qr-code";

    private static final String QRIS_ACQUIRER = "gopay";

    private final String serverKey;
    private final String baseUrl;
    private final String authorization;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public MidtransPaymentGatewayAdapter(
        @Value("${midtrans.server-key}") String serverKey,
        @Value("${midtrans.base-url}") String baseUrl,
        @Value("${midtrans.connect-timeout-ms:5000}") long connectTimeoutMillis,
        @Value("${midtrans.request-timeout-ms:15000}") long requestTimeoutMillis,
        ObjectMapper objectMapper
    ) {
        if (serverKey == null || serverKey.isBlank()) {
            throw new IllegalStateException("midtrans.server-key is required");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("midtrans.base-url is required");
        }
        this.serverKey = serverKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authorization = "Basic " + Base64.getEncoder()
            .encodeToString((serverKey + ":").getBytes(StandardCharsets.UTF_8));
        this.requestTimeout = Duration.ofMillis(requestTimeoutMillis);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
            .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public GatewayCharge charge(GatewayChargeRequest request) {
        String body;
        try {
            body = objectMapper.writeValueAsString(chargeBody(request));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("a charge body built from plain values could not be written", impossible);
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(
                request("/v2/charge")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
        } catch (HttpTimeoutException timeout) {
            return unknownCharge(null, "Midtrans did not answer within " + requestTimeout.toMillis()
                + "ms; the charge may exist"
            );
        } catch (IOException unreachable) {
            return unknownCharge(null, "Midtrans could not be reached ("
                + unreachable.getClass().getSimpleName() + "); the charge may exist"
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unknownCharge(null, "interrupted while waiting for Midtrans; the charge may exist");
        }
        return classifyCharge(request, response);
    }

    @Override
    public GatewayStatus statusOf(String referenceNumber) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(
                request("/v2/" + URLEncoder.encode(referenceNumber, StandardCharsets.UTF_8) + "/status")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
        } catch (HttpTimeoutException timeout) {
            return unknownStatus(null, "Midtrans did not answer a status query within "
                + requestTimeout.toMillis() + "ms"
            );
        } catch (IOException unreachable) {
            return unknownStatus(null, "Midtrans could not be reached for a status query ("
                + unreachable.getClass().getSimpleName() + ")"
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unknownStatus(null, "interrupted while asking Midtrans for a status");
        }
        return classifyStatus(response);
    }

    @Override
    public PaymentInstructions instructionsFrom(String gatewayResponse) {
        JsonNode json = parse(gatewayResponse);
        return json == null ? PaymentInstructions.none() : instructionsOf(json);
    }

    @Override
    public boolean isAuthentic(GatewayNotification notification) {
        if (notification == null
            || blank(notification.orderId())
            || blank(notification.statusCode())
            || blank(notification.grossAmount())
            || blank(notification.signatureKey())
        ) {
            return false;
        }
        String expected = MidtransSignature.of(
            notification.orderId(), notification.statusCode(), notification.grossAmount(), serverKey
        );
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII),
            notification.signatureKey().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII)
        );
    }

    static GatewaySettlement settlementOf(String transactionStatus, String fraudStatus) {
        if (transactionStatus == null) {
            return GatewaySettlement.UNKNOWN;
        }
        String fraud = fraudStatus == null ? null : fraudStatus.toLowerCase(Locale.ROOT);
        return switch (transactionStatus.toLowerCase(Locale.ROOT)) {
            case "settlement", "capture" -> {
                if (fraud == null || fraud.equals("accept")) {
                    yield GatewaySettlement.SETTLED;
                }
                yield fraud.equals("deny") ? GatewaySettlement.FAILED : GatewaySettlement.PENDING;
            }
            case "pending", "authorize" -> GatewaySettlement.PENDING;
            case "deny", "cancel", "failure" -> GatewaySettlement.FAILED;
            case "expire" -> GatewaySettlement.EXPIRED;
            default -> GatewaySettlement.UNKNOWN;
        };
    }

    private Map<String, Object> chargeBody(GatewayChargeRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        switch (request.method()) {
            case MIDTRANS_VA -> {
                if (request.bank() == null) {
                    throw new IllegalArgumentException("a virtual-account charge needs a bank");
                }
                if (request.bank() == VirtualAccountBank.PERMATA) {
                    body.put("payment_type", "permata");
                } else {
                    body.put("payment_type", "bank_transfer");
                    body.put("bank_transfer", Map.of("bank", request.bank().name().toLowerCase(Locale.ROOT)));
                }
            }
            case MIDTRANS_QRIS -> {
                body.put("payment_type", "qris");
                body.put("qris", Map.of("acquirer", QRIS_ACQUIRER));
            }
            default -> throw new IllegalArgumentException(
                "Midtrans charges virtual accounts and QRIS here, not " + request.method()
            );
        }
        body.put("transaction_details", Map.of(
            "order_id", request.referenceNumber(),
            "gross_amount", request.grossAmountRupiah()
        ));
        return body;
    }

    private GatewayCharge classifyCharge(GatewayChargeRequest request, HttpResponse<String> response) {
        int http = response.statusCode();
        String body = response.body();
        JsonNode json = parse(body);
        if (json == null) {
            return unknownCharge(body, "Midtrans answered HTTP " + http + " with a body that is not a JSON object");
        }

        int code = statusCodeOf(json, http);
        if (http >= 500 || code >= 500) {
            return unknownCharge(body, "Midtrans reported a server error (HTTP " + http + ", status_code " + code + ")");
        }
        if (code == 406) {
            return unknownCharge(body, "Midtrans already holds an order " + request.referenceNumber()
                + " (406), and this answer does not say what became of it"
            );
        }
        if (isSuccess(http) && isSuccess(code)) {
            if (text(json, "transaction_status") == null) {
                return unknownCharge(body, "Midtrans accepted the request but named no transaction status");
            }
            return new GatewayCharge(
                GatewayChargeOutcome.ACCEPTED, text(json, "transaction_id"), instructionsOf(json), body, null
            );
        }
        if (code >= 400 && code < 500) {
            return new GatewayCharge(GatewayChargeOutcome.REFUSED, null, PaymentInstructions.none(), body,
                "Midtrans refused the charge with status_code " + code + ": " + text(json, "status_message")
            );
        }
        return unknownCharge(body, "Midtrans answered HTTP " + http + " with status_code " + code
            + ", which is neither a created transaction nor a refusal"
        );
    }

    private GatewayStatus classifyStatus(HttpResponse<String> response) {
        int http = response.statusCode();
        String body = response.body();
        JsonNode json = parse(body);
        if (json == null) {
            return unknownStatus(body, "Midtrans answered a status query with a body that is not a JSON object");
        }

        int code = statusCodeOf(json, http);
        if (code == 404) {
            return new GatewayStatus(GatewaySettlement.NOT_FOUND, null, null, body,
                "Midtrans has no transaction under this order id"
            );
        }
        if (http >= 500 || code >= 500) {
            return unknownStatus(body, "Midtrans reported a server error to a status query (HTTP " + http + ")");
        }

        String transactionStatus = text(json, "transaction_status");
        if (transactionStatus == null) {
            return unknownStatus(body, "Midtrans answered a status query with HTTP " + http + " and status_code "
                + code + " but no transaction status"
            );
        }
        GatewaySettlement settlement = settlementOf(transactionStatus, text(json, "fraud_status"));
        return new GatewayStatus(
            settlement,
            decimal(json, "gross_amount"),
            text(json, "transaction_id"),
            body,
            settlement == GatewaySettlement.UNKNOWN
                ? "transaction_status '" + transactionStatus + "' is not a state a top-up can act on"
                : null
        );
    }

    private PaymentInstructions instructionsOf(JsonNode json) {
        String expiresAt = text(json, "expiry_time");

        JsonNode virtualAccounts = json.get("va_numbers");
        if (virtualAccounts != null && virtualAccounts.isArray() && !virtualAccounts.isEmpty()) {
            JsonNode first = virtualAccounts.get(0);
            return new PaymentInstructions(bankOf(text(first, "bank")), text(first, "va_number"), null, expiresAt);
        }

        String permata = text(json, "permata_va_number");
        if (permata != null) {
            return new PaymentInstructions(VirtualAccountBank.PERMATA, permata, null, expiresAt);
        }

        JsonNode actions = json.get("actions");
        if (actions != null && actions.isArray()) {
            for (JsonNode action : actions) {
                if (QR_CODE_ACTION.equals(text(action, "name"))) {
                    return new PaymentInstructions(null, null, text(action, "url"), expiresAt);
                }
            }
        }
        return PaymentInstructions.none();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(requestTimeout)
            .header("Authorization", authorization)
            .header("Accept", "application/json");
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode json = objectMapper.readTree(body);
            return json != null && json.isObject() ? json : null;
        } catch (JsonProcessingException unreadable) {
            return null;
        }
    }

    private static int statusCodeOf(JsonNode json, int http) {
        String declared = text(json, "status_code");
        if (declared == null) {
            return http;
        }
        try {
            return Integer.parseInt(declared.trim());
        } catch (NumberFormatException unreadable) {
            return http;
        }
    }

    private static String text(JsonNode json, String field) {
        JsonNode value = json.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static BigDecimal decimal(JsonNode json, String field) {
        String value = text(json, field);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException unreadable) {
            return null;
        }
    }

    private static VirtualAccountBank bankOf(String code) {
        if (code == null) {
            return null;
        }
        try {
            return VirtualAccountBank.valueOf(code.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unrecognised) {
            return null;
        }
    }

    private static boolean isSuccess(int code) {
        return code >= 200 && code < 300;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static GatewayCharge unknownCharge(String body, String detail) {
        return new GatewayCharge(GatewayChargeOutcome.UNKNOWN, null, PaymentInstructions.none(), body, detail);
    }

    private static GatewayStatus unknownStatus(String body, String detail) {
        return new GatewayStatus(GatewaySettlement.UNKNOWN, null, null, body, detail);
    }
}
