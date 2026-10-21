package com.kinetix.payment.api.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kinetix.payment.application.TopUpService;
import com.kinetix.payment.application.WalletService;
import com.kinetix.payment.domain.port.PaymentGatewayPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
class IdentityOutageThroughTheFilterChainTest {
    private static final String CUSTOMER = "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7";

    private static final IdentityKeys IDENTITY = IdentityKeys.unreachable();

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

    @Test
    void aTokenThatCannotBeCheckedBecauseIdentityIsDownIs503EveryTimeNot401() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/api/v1/payment/wallet/customer/balance")
                )
                    .header("Authorization", "Bearer " + IDENTITY.tokenFor(CUSTOMER, "customer"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            String seen = "attempt " + attempt + ": HTTP " + response.statusCode()
                + " body '" + response.body() + "'";
            assertEquals(503, response.statusCode(), seen);

            JsonNode body = json.readTree(response.body());
            assertTrue(body != null && body.hasNonNull("error"), "the body does not carry an error: " + seen);
        }

        verifyNoInteractions(walletService);
    }
}
