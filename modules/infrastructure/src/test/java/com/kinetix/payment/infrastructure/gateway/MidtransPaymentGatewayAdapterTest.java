package com.kinetix.payment.infrastructure.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kinetix.payment.domain.entity.PaymentTransaction.PaymentMethod;
import com.kinetix.payment.domain.gateway.GatewayCharge;
import com.kinetix.payment.domain.gateway.GatewayChargeOutcome;
import com.kinetix.payment.domain.gateway.GatewayChargeRequest;
import com.kinetix.payment.domain.gateway.GatewayNotification;
import com.kinetix.payment.domain.gateway.GatewaySettlement;
import com.kinetix.payment.domain.gateway.GatewayStatus;
import com.kinetix.payment.domain.gateway.VirtualAccountBank;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MidtransPaymentGatewayAdapterTest {
    private static final String SERVER_KEY = "SB-Mid-server-TEST-ONLY";

    // SHA-512 of "TOPUP-1" + "200" + "50000.00" + SERVER_KEY, computed outside this code with shasum.
    private static final String SIGNATURE = "e40b5d36a66ebe286cfa5fc036e1ba290d03cb3580dacc71160fa1f7415735f9132e6601d981d009880ad5d65fb5d22aa6a80cfa52ac498923a24726abb4cd60";

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();

    private volatile int answerStatus = 200;
    private volatile String answerBody = "{}";
    private volatile long answerDelayMillis;

    private HttpServer server;
    private ExecutorService handlers;
    private MidtransPaymentGatewayAdapter adapter;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastPath.set(exchange.getRequestURI().getPath());
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (answerDelayMillis > 0) {
                try {
                    Thread.sleep(answerDelayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = answerBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answerStatus, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.start();
        adapter = adapterWithRequestTimeout(2_000);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        handlers.shutdownNow();
    }

    @Test
    void aVirtualAccountChargeIsPostedToV2ChargeWithTheBankTheWholeAmountAndTheServerKey() throws Exception {
        answer(200, """
            {"status_code":"201","status_message":"Success, Bank Transfer transaction is created",
             "transaction_id":"be03df7d","order_id":"TOPUP-1","gross_amount":"50000.00",
             "transaction_status":"pending","va_numbers":[{"bank":"bca","va_number":"91019021579"}]}
            """);

        GatewayCharge charge = adapter.charge(
            new GatewayChargeRequest("TOPUP-1", 50000L, PaymentMethod.MIDTRANS_VA, VirtualAccountBank.BCA)
        );

        assertEquals(GatewayChargeOutcome.ACCEPTED, charge.outcome());
        assertEquals("be03df7d", charge.externalTransactionId());
        assertEquals("91019021579", charge.instructions().virtualAccountNumber());
        assertEquals(VirtualAccountBank.BCA, charge.instructions().bank());
        assertEquals("POST", lastMethod.get());
        assertEquals("/v2/charge", lastPath.get());
        assertEquals(
            "Basic " + Base64.getEncoder().encodeToString((SERVER_KEY + ":").getBytes(StandardCharsets.UTF_8)),
            lastAuthorization.get()
        );
        JsonNode sent = json.readTree(lastBody.get());
        assertEquals("bank_transfer", sent.path("payment_type").asText());
        assertEquals("bca", sent.path("bank_transfer").path("bank").asText());
        assertEquals("TOPUP-1", sent.path("transaction_details").path("order_id").asText());
        assertTrue(sent.path("transaction_details").path("gross_amount").isIntegralNumber());
        assertEquals(50000L, sent.path("transaction_details").path("gross_amount").asLong());
        assertFalse(sent.has("customer_details"), "no customer detail is invented for the gateway");
    }

    @Test
    void permataIsItsOwnPaymentTypeAndAnswersWithItsOwnField() throws Exception {
        answer(200, """
            {"status_code":"201","transaction_id":"p1","transaction_status":"pending",
             "permata_va_number":"8562000087926752"}
            """);

        GatewayCharge charge = adapter.charge(
            new GatewayChargeRequest("TOPUP-2", 25000L, PaymentMethod.MIDTRANS_VA, VirtualAccountBank.PERMATA)
        );

        assertEquals("8562000087926752", charge.instructions().virtualAccountNumber());
        assertEquals("permata", json.readTree(lastBody.get()).path("payment_type").asText());
    }

    @Test
    void aQrisChargeIsPaymentTypeQrisAndAnswersWithTheQrCodeAction() throws Exception {
        answer(200, """
            {"status_code":"201","transaction_id":"0d8178e1","transaction_status":"pending",
             "actions":[{"name":"generate-qr-code","method":"GET",
                         "url":"https://api.sandbox.midtrans.com/v2/qris/0d8178e1/qr-code"}]}
            """);

        GatewayCharge charge = adapter.charge(
            new GatewayChargeRequest("TOPUP-3", 75000L, PaymentMethod.MIDTRANS_QRIS, null)
        );

        assertEquals(GatewayChargeOutcome.ACCEPTED, charge.outcome());
        assertEquals("https://api.sandbox.midtrans.com/v2/qris/0d8178e1/qr-code", charge.instructions().qrCodeUrl());
        JsonNode sent = json.readTree(lastBody.get());
        assertEquals("qris", sent.path("payment_type").asText());
        assertEquals("gopay", sent.path("qris").path("acquirer").asText());
    }

    @Test
    void aDuplicateOrderIdIsUnknownBecauseTheChargeMayAlreadyExist() {
        answer(200, "{\"status_code\":\"406\",\"status_message\":\"Duplicate order ID\"}");

        assertEquals(GatewayChargeOutcome.UNKNOWN, adapter.charge(vaRequest()).outcome());
    }

    @Test
    void aValidationErrorIsADefiniteRefusal() {
        answer(200, "{\"status_code\":\"400\",\"status_message\":\"Validation Error\"}");

        GatewayCharge charge = adapter.charge(vaRequest());

        assertEquals(GatewayChargeOutcome.REFUSED, charge.outcome());
        assertTrue(charge.detail().contains("400"));
    }

    @Test
    void aServerErrorIsUnknownNotRefused() {
        answer(500, "{\"status_code\":\"500\",\"status_message\":\"Internal Server Error\"}");

        assertEquals(GatewayChargeOutcome.UNKNOWN, adapter.charge(vaRequest()).outcome());
    }

    @Test
    void aSuccessfulStatusWithABodyThatIsNotJsonIsUnknownNotAccepted() {
        answer(200, "<html>maintenance</html>");

        assertEquals(GatewayChargeOutcome.UNKNOWN, adapter.charge(vaRequest()).outcome());
    }

    @Test
    void aGatewayThatNeverAnswersIsBoundedAndUnknown() {
        adapter = adapterWithRequestTimeout(200);
        answerDelayMillis = 2_000;
        answer(200, "{\"status_code\":\"201\",\"transaction_status\":\"pending\"}");

        long started = System.nanoTime();
        GatewayCharge charge = adapter.charge(vaRequest());
        long tookMillis = (System.nanoTime() - started) / 1_000_000;

        assertEquals(GatewayChargeOutcome.UNKNOWN, charge.outcome());
        assertTrue(tookMillis < 1_500, "the request was not bounded: it took " + tookMillis + "ms");
    }

    @Test
    void anUnreachableGatewayIsUnknown() {
        server.stop(0);

        assertEquals(GatewayChargeOutcome.UNKNOWN, adapter.charge(vaRequest()).outcome());
    }

    @Test
    void aSettledTransactionIsReadFromTheV2StatusEndpoint() {
        answer(200, """
            {"status_code":"200","transaction_id":"be03df7d","order_id":"TOPUP-1",
             "gross_amount":"50000.00","transaction_status":"settlement"}
            """);

        GatewayStatus status = adapter.statusOf("TOPUP-1");

        assertEquals(GatewaySettlement.SETTLED, status.settlement());
        assertEquals(0, new BigDecimal("50000.00").compareTo(status.grossAmount()));
        assertEquals("GET", lastMethod.get());
        assertEquals("/v2/TOPUP-1/status", lastPath.get());
    }

    @Test
    void anExpiredTransactionReportedWith407IsAnAnswerNotAFailureToAnswer() {
        answer(407, "{\"status_code\":\"407\",\"transaction_status\":\"expire\",\"gross_amount\":\"50000.00\"}");

        assertEquals(GatewaySettlement.EXPIRED, adapter.statusOf("TOPUP-1").settlement());
    }

    @Test
    void aTransactionMidtransDoesNotHaveIsNotFound() {
        answer(404, "{\"status_code\":\"404\",\"status_message\":\"Transaction doesn't exist.\"}");

        assertEquals(GatewaySettlement.NOT_FOUND, adapter.statusOf("TOPUP-1").settlement());
    }

    @Test
    void aStatusQueryThatFailsDecidesNothing() {
        answer(503, "{\"status_code\":\"503\"}");

        assertEquals(GatewaySettlement.UNKNOWN, adapter.statusOf("TOPUP-1").settlement());
    }

    @Test
    void onlySettlementOrCaptureClearedByFraudScreeningCountsAsPaid() {
        assertEquals(GatewaySettlement.SETTLED, MidtransPaymentGatewayAdapter.settlementOf("settlement", null));
        assertEquals(GatewaySettlement.SETTLED, MidtransPaymentGatewayAdapter.settlementOf("capture", "accept"));
        assertEquals(GatewaySettlement.PENDING, MidtransPaymentGatewayAdapter.settlementOf("capture", "challenge"));
        assertEquals(GatewaySettlement.FAILED, MidtransPaymentGatewayAdapter.settlementOf("capture", "deny"));
        assertEquals(GatewaySettlement.PENDING, MidtransPaymentGatewayAdapter.settlementOf("PENDING", null));
        assertEquals(GatewaySettlement.FAILED, MidtransPaymentGatewayAdapter.settlementOf("deny", null));
        assertEquals(GatewaySettlement.FAILED, MidtransPaymentGatewayAdapter.settlementOf("cancel", null));
        assertEquals(GatewaySettlement.FAILED, MidtransPaymentGatewayAdapter.settlementOf("failure", null));
        assertEquals(GatewaySettlement.EXPIRED, MidtransPaymentGatewayAdapter.settlementOf("expire", null));
        assertEquals(GatewaySettlement.UNKNOWN, MidtransPaymentGatewayAdapter.settlementOf("refund", null));
        assertEquals(GatewaySettlement.UNKNOWN, MidtransPaymentGatewayAdapter.settlementOf(null, null));
    }

    @Test
    void aNotificationSignedWithTheServerKeyIsAuthentic() {
        assertTrue(adapter.isAuthentic(new GatewayNotification("TOPUP-1", "200", "50000.00", SIGNATURE)));
        assertTrue(adapter.isAuthentic(new GatewayNotification("TOPUP-1", "200", "50000.00", SIGNATURE.toUpperCase())));
    }

    @Test
    void aNotificationWithAnyOfItsSignedFieldsChangedIsNotAuthentic() {
        assertFalse(adapter.isAuthentic(new GatewayNotification("TOPUP-1", "200", "5000000.00", SIGNATURE)));
        assertFalse(adapter.isAuthentic(new GatewayNotification("TOPUP-2", "200", "50000.00", SIGNATURE)));
        assertFalse(adapter.isAuthentic(new GatewayNotification("TOPUP-1", "201", "50000.00", SIGNATURE)));
        assertFalse(adapter.isAuthentic(new GatewayNotification("TOPUP-1", "200", "50000.00", null)));
        assertFalse(adapter.isAuthentic(null));
    }

    private MidtransPaymentGatewayAdapter adapterWithRequestTimeout(long requestTimeoutMillis) {
        return new MidtransPaymentGatewayAdapter(
            SERVER_KEY, "http://127.0.0.1:" + server.getAddress().getPort() + "/", 1_000, requestTimeoutMillis, json
        );
    }

    private void answer(int status, String body) {
        answerStatus = status;
        answerBody = body;
    }

    private static GatewayChargeRequest vaRequest() {
        return new GatewayChargeRequest("TOPUP-9", 50000L, PaymentMethod.MIDTRANS_VA, VirtualAccountBank.BNI);
    }
}
