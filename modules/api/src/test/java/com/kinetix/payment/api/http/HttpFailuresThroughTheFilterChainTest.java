package com.kinetix.payment.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kinetix.payment.application.TopUpService;
import com.kinetix.payment.application.WalletService;
import com.kinetix.payment.domain.exception.TopUpLimitExceededException;
import com.kinetix.payment.domain.port.PaymentGatewayPort;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    classes = HttpFailureProbeApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.grpc.server.enabled=false",
        "kinetix.jwt.issuer=" + IdentityKeys.ISSUER,
        "kinetix.jwt.audience=" + IdentityKeys.AUDIENCE
    }
)
class HttpFailuresThroughTheFilterChainTest {
    private static final String CUSTOMER = "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7";

    private static final String TOP_UP = "/api/v1/payment/wallet/customer/topup";

    private static final String BALANCE = "/api/v1/payment/wallet/customer/balance";

    private static final String NOTIFICATIONS = "/api/v1/payment/gateway/midtrans/notifications";

    private static final String KONG_UPSTREAM_HOST = "kinetix-payment-service:8003";

    private static final String INTERNAL_NAME = "kinetix-payment-service";

    private static final String TOP_UP_BODY =
        "{\"amount\": 50000, \"paymentMethod\": \"MIDTRANS_VA\", \"bank\": \"BCA\"}";

    private static final IdentityKeys IDENTITY = IdentityKeys.serving();

    private final HttpClient http = HttpClient.newHttpClient();

    private final ObjectMapper json = new ObjectMapper();

    @Value("${local.server.port}")
    private int port;

    @MockitoBean
    private TopUpService topUpService;

    @MockitoBean
    private WalletService walletService;

    @MockitoBean
    private PaymentGatewayPort gateway;

    @DynamicPropertySource
    static void identity(DynamicPropertyRegistry registry) {
        registry.add("kinetix.jwt.jwks-uri", IDENTITY::jwksUri);
    }

    @AfterAll
    static void stopIdentity() {
        IDENTITY.stop();
    }

    @Test
    void aTopUpWithoutAnIdempotencyKeyIs400InTheServiceShape() throws Exception {
        HttpResponse<String> response = send(
            signedIn(TOP_UP)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(TOP_UP_BODY))
        );

        assertRefusal(response, 400);
        verifyNoInteractions(topUpService);
    }

    @Test
    void aTopUpBodyThatIsNotJsonIs400InTheServiceShape() throws Exception {
        HttpResponse<String> response = send(topUpWith("{\"amount\": 50000,"));

        assertRefusal(response, 400);
        verifyNoInteractions(topUpService);
    }

    @Test
    void aTopUpWithAnUnknownPaymentMethodIs400InTheServiceShape() throws Exception {
        HttpResponse<String> response = send(
            topUpWith("{\"amount\": 50000, \"paymentMethod\": \"PAYPAL\", \"bank\": \"BCA\"}")
        );

        assertRefusal(response, 400);
        verifyNoInteractions(topUpService);
    }

    @Test
    void aTopUpBelowTheMinimumIs400AndSaysWhy() throws Exception {
        HttpResponse<String> response = send(
            topUpWith("{\"amount\": 10, \"paymentMethod\": \"MIDTRANS_VA\", \"bank\": \"BCA\"}")
        );

        JsonNode body = assertRefusal(response, 400);
        assertTrue(
            body.get("error").asString().contains("Minimum top-up amount is IDR 1,000"),
            "the refusal does not say why: " + response.body()
        );
        verifyNoInteractions(topUpService);
    }

    @Test
    void aMethodTheRouteDoesNotServeIs405() throws Exception {
        HttpResponse<String> response = send(signedIn(TOP_UP).GET());

        assertRefusal(response, 405);
    }

    @Test
    void aTopUpThatIsNotJsonByContentTypeIs415() throws Exception {
        HttpResponse<String> response = send(
            signedIn(TOP_UP)
                .header("Idempotency-Key", "k-415")
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(TOP_UP_BODY))
        );

        assertRefusal(response, 415);
        verifyNoInteractions(topUpService);
    }

    @Test
    void aBalanceReadDuringADatabaseOutageIs503() throws Exception {
        when(walletService.findCustomerWallet(any())).thenThrow(
            new DataAccessResourceFailureException("Unable to acquire JDBC Connection")
        );

        HttpResponse<String> response = send(signedIn(BALANCE).GET());

        assertRefusal(response, 503);
    }

    @Test
    void aTopUpWhoseTransactionCannotBeOpenedIs503() throws Exception {
        when(topUpService.requestTopUp(any())).thenThrow(
            new CannotCreateTransactionException("Could not open JPA EntityManager for transaction")
        );

        HttpResponse<String> response = send(topUpWith(TOP_UP_BODY));

        assertRefusal(response, 503);
    }

    @Test
    void aRequestWithoutATokenIs401InTheServiceShape() throws Exception {
        HttpResponse<String> response = send(request(BALANCE).GET());

        assertRefusal(response, 401);
    }

    @Test
    void aTokenSignedByAKeyIdentityDoesNotHoldIs401EveryTime() throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpResponse<String> response = send(
                request(BALANCE)
                    .header("Authorization", "Bearer " + IDENTITY.tokenWithAnUnknownKey(CUSTOMER, "customer"))
                    .GET()
            );

            assertRefusal(response, 401);
        }
    }

    @Test
    void aRequestWithoutATokenForwardedByKongIsChallengedWithoutNamingPaymentsInternalHost() throws Exception {
        String answer = sendAsKongForwards(BALANCE, null);

        assertEquals("HTTP/1.1 401", statusOf(answer), answer);
        assertEquals("Bearer", headerOf(answer, "WWW-Authenticate"), answer);
        assertFalse(answer.contains(INTERNAL_NAME), "the 401 names payment's internal host: " + answer);
    }

    @Test
    void aRefusedTokenForwardedByKongIsChallengedWithoutNamingPaymentsInternalHost() throws Exception {
        String answer = sendAsKongForwards(BALANCE, "Bearer " + IDENTITY.refreshTokenFor(CUSTOMER, "customer"));

        assertEquals("HTTP/1.1 401", statusOf(answer), answer);
        assertEquals("Bearer error=\"invalid_token\"", headerOf(answer, "WWW-Authenticate"), answer);
        assertFalse(answer.contains(INTERNAL_NAME), "the 401 names payment's internal host: " + answer);
    }

    @Test
    void aNotificationThatIsNotJsonIs400NotAnEmpty401() throws Exception {
        HttpResponse<String> response = send(notification("{\"order_id\":"));

        assertRefusal(response, 400);
        verifyNoInteractions(topUpService);
    }

    @Test
    void aNotificationDuringADatabaseOutageIs503SoMidtransRetries() throws Exception {
        when(gateway.isAuthentic(any())).thenReturn(true);
        when(topUpService.settle(any())).thenThrow(
            new DataAccessResourceFailureException("Unable to acquire JDBC Connection")
        );

        HttpResponse<String> response = send(
            notification(
                "{\"order_id\":\"TOPUP-1\",\"status_code\":\"200\",\"gross_amount\":\"50000.00\","
                    + "\"signature_key\":\"abc\",\"transaction_status\":\"settlement\"}"
            )
        );

        assertRefusal(response, 503);
    }

    @Test
    void anUnexpectedFailureInANotificationIs500NotAnEmpty401() throws Exception {
        when(gateway.isAuthentic(any())).thenReturn(true);
        when(topUpService.settle(any())).thenThrow(new IllegalStateException("a defect"));

        HttpResponse<String> response = send(
            notification(
                "{\"order_id\":\"TOPUP-1\",\"status_code\":\"200\",\"gross_amount\":\"50000.00\","
                    + "\"signature_key\":\"abc\",\"transaction_status\":\"settlement\"}"
            )
        );

        JsonNode body = assertRefusal(response, 500);
        assertFalse(body.get("error").asString().contains("a defect"), "the failure leaked: " + response.body());
    }

    @Test
    void anUnexpectedFailureForASignedInCallerIs500InTheServiceShape() throws Exception {
        when(walletService.findCustomerWallet(any())).thenThrow(new IllegalStateException("a defect"));

        HttpResponse<String> response = send(signedIn(BALANCE).GET());

        assertRefusal(response, 500);
    }

    @Test
    void anAdminIsRefusedACustomerWalletThroughTheFilterChain() throws Exception {
        HttpResponse<String> response = send(
            request(BALANCE).header("Authorization", "Bearer " + IDENTITY.tokenFor(CUSTOMER, "admin")).GET()
        );

        assertRefusal(response, 403);
        verifyNoInteractions(walletService);
    }

    @Test
    void aTopUpOverALimitIs422WithItsCodeAndTheLimit() throws Exception {
        when(topUpService.requestTopUp(any())).thenThrow(
            new TopUpLimitExceededException(
                TopUpLimitExceededException.WALLET_BALANCE,
                new BigDecimal("2000000"),
                "this top-up would bring the wallet to IDR 2,050,000, above the IDR 2,000,000 a customer "
                    + "wallet may hold"
            )
        );

        HttpResponse<String> response = send(topUpWith(TOP_UP_BODY));

        JsonNode body = assertRefusal(response, 422);
        assertEquals("WALLET_BALANCE_LIMIT_EXCEEDED", body.get("code").asString(), response.body());
        assertEquals("2000000", body.get("limit").asString(), response.body());
    }

    private JsonNode assertRefusal(HttpResponse<String> response, int status) {
        JsonNode body = assertErrorBody(response, status);
        assertFalse(
            body.has("timestamp") || body.has("path"),
            "the framework's error page answered instead of the service: " + response.body()
        );

        return body;
    }

    private JsonNode assertErrorBody(HttpResponse<String> response, int status) {
        String seen = "HTTP " + response.statusCode() + " body '" + response.body() + "'";
        assertEquals(status, response.statusCode(), seen);

        JsonNode body;
        try {
            body = json.readTree(response.body());
        } catch (RuntimeException unreadable) {
            throw new AssertionError("the body is not JSON: " + seen, unreadable);
        }

        assertTrue(
            body != null && body.hasNonNull("error") && !body.get("error").asString().isBlank(),
            "the body does not carry an error: " + seen
        );

        return body;
    }

    private HttpRequest.Builder topUpWith(String body) {
        return signedIn(TOP_UP)
            .header("Idempotency-Key", "k-" + body.hashCode())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpRequest.Builder notification(String body) {
        return request(NOTIFICATIONS)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpRequest.Builder signedIn(String path) {
        return request(path).header("Authorization", "Bearer " + IDENTITY.tokenFor(CUSTOMER, "customer"));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String sendAsKongForwards(String path, String authorization) throws IOException {
        StringBuilder head = new StringBuilder()
            .append("GET ").append(path).append(" HTTP/1.1\r\n")
            .append("Host: ").append(KONG_UPSTREAM_HOST).append("\r\n")
            .append("X-Forwarded-For: 203.0.113.7\r\n")
            .append("X-Forwarded-Proto: https\r\n")
            .append("X-Forwarded-Host: api.kinetix.example\r\n")
            .append("X-Forwarded-Port: 443\r\n")
            .append("X-Forwarded-Path: ").append(path).append("\r\n")
            .append("X-Real-IP: 203.0.113.7\r\n");
        if (authorization != null) {
            head.append("Authorization: ").append(authorization).append("\r\n");
        }

        head.append("Connection: close\r\n\r\n");

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();

            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String statusOf(String answer) {
        String statusLine = answer.lines().findFirst().orElse("");
        return statusLine.length() < 12 ? statusLine : statusLine.substring(0, 12);
    }

    private static String headerOf(String answer, String name) {
        String prefix = name.toLowerCase(Locale.ROOT) + ":";
        String[] head = answer.split("\r\n\r\n", 2)[0].split("\r\n");

        return Arrays.stream(head)
            .skip(1)
            .filter(line -> line.toLowerCase(Locale.ROOT).startsWith(prefix))
            .map(line -> line.substring(prefix.length()).trim())
            .findFirst()
            .orElse(null);
    }
}
