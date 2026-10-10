package com.kinetix.payment.api.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonLogFormatterTest {
    private static final String REQUEST_ID = "kinetix-trace-1757000000-31337";

    private final JsonLogFormatter formatter = new JsonLogFormatter();

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aPlainGrepForTheCorrelationIdStillMatchesTheLine() {
        String line = formatter.format(event(
            "gRPC payment.v1.PaymentService/CreateEscrowHold from order (request_id="
                + REQUEST_ID + ")",
            Map.of(RequestIdFilter.MDC_KEY, REQUEST_ID)
        ));

        assertTrue(line.contains(REQUEST_ID), line);
    }

    @Test
    void oneEventIsOneLineOfOneJsonObject() throws Exception {
        String line = formatter.format(event("escrow held", Map.of(RequestIdFilter.MDC_KEY, REQUEST_ID)));

        assertTrue(line.endsWith("\n"), line);
        assertEquals(-1, line.substring(0, line.length() - 1).indexOf('\n'), line);

        JsonNode json = mapper.readTree(line);
        assertEquals("escrow held", json.get("message").asString());
        assertEquals("INFO", json.get("level").asString());
        assertEquals("com.kinetix.payment.Probe", json.get("logger").asString());
        assertEquals(REQUEST_ID, json.get("request_id").asString());
        assertTrue(json.get("timestamp").asString().endsWith("Z"), line);
    }

    @Test
    void workThatBelongsToNoRequestSaysSoRatherThanInventingAnId() throws Exception {
        String line = formatter.format(event("starting escrow sweep", Map.of()));

        JsonNode json = mapper.readTree(line);
        assertTrue(json.has("request_id"), line);
        assertNull(json.get("request_id").stringValue(), line);
    }

    @Test
    void aStackTraceStaysInsideTheOneLine() throws Exception {
        LoggingEvent event = event("escrow call failed", Map.of(RequestIdFilter.MDC_KEY, REQUEST_ID));
        event.setThrowableProxy(new ThrowableProxy(new IllegalStateException("no such hold")));

        String line = formatter.format(event);

        assertEquals(-1, line.substring(0, line.length() - 1).indexOf('\n'), line);
        JsonNode json = mapper.readTree(line);
        assertEquals("java.lang.IllegalStateException", json.get("error_type").asString());
        assertEquals("no such hold", json.get("error_message").asString());
        assertTrue(json.get("error_stack_trace").asString().contains("IllegalStateException"), line);
        assertTrue(line.contains(REQUEST_ID), line);
    }

    private static LoggingEvent event(String message, Map<String, String> mdc) {
        LoggingEvent event = new LoggingEvent();
        event.setLoggerName("com.kinetix.payment.Probe");
        event.setLevel(Level.INFO);
        event.setMessage(message);
        event.setThreadName("main");
        event.setTimeStamp(System.currentTimeMillis());
        event.setMDCPropertyMap(mdc);
        return event;
    }
}
