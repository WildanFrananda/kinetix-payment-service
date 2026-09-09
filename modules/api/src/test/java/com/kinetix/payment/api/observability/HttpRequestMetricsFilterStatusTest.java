package com.kinetix.payment.api.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HttpRequestMetricsFilterStatusTest {
    private PrometheusMeterRegistry registry;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        registry = new MetricsConfiguration()
            .prometheusMeterRegistry("kinetix-payment-service", Optional.empty());

        mvc = MockMvcBuilders
            .standaloneSetup(new FailingProbeController(), new MetricsController(registry))
            .addFilters(new HttpRequestMetricsFilter(registry))
            .build();
    }

    @Test
    void anExceptionThatEscapesTheChainIsCountedAsTheFiveHundredTheClientReceives()
        throws Exception {
        assertThrows(
            Exception.class,
            () -> mvc.perform(get("/api/v1/probe/unhandled-failure")),
            "the probe must let the exception escape the filter chain"
        );

        String exposition = scrape();

        assertTrue(
            exposition.contains(
                "kinetix_http_requests_total{method=\"GET\","
                    + "route=\"/api/v1/probe/unhandled-failure\",status=\"500\"} 1.0"),
            "a failed request produced no 5xx series, so an alert on payment's error rate "
                + "can never fire:\n" + exposition
        );
        assertFalse(
            exposition.contains("route=\"/api/v1/probe/unhandled-failure\",status=\"200\""),
            "a request the client saw fail was counted as a success:\n" + exposition
        );
    }

    @Test
    void aFailureAfterTheResponseIsCommittedKeepsTheStatusAlreadyOnTheWire() throws Exception {
        assertThrows(
            Exception.class,
            () -> mvc.perform(get("/api/v1/probe/committed-failure"))
        );

        String exposition = scrape();

        assertTrue(
            exposition.contains(
                "kinetix_http_requests_total{method=\"GET\","
                    + "route=\"/api/v1/probe/committed-failure\",status=\"202\"} 1.0"),
            "the container cannot rewrite a committed status, so neither may the counter:\n"
                + exposition
        );
        assertFalse(
            exposition.contains("route=\"/api/v1/probe/committed-failure\",status=\"500\""),
            "a 500 was invented for a response the client received as 202:\n" + exposition
        );
    }

    @Test
    void aFailedRequestIsStillTimedUnderItsOwnRoute() throws Exception {
        assertThrows(
            Exception.class,
            () -> mvc.perform(get("/api/v1/probe/unhandled-failure"))
        );

        String exposition = scrape();

        assertTrue(
            exposition.contains(
                "kinetix_http_request_duration_seconds_count{method=\"GET\","
                    + "route=\"/api/v1/probe/unhandled-failure\"} 1"),
            "a failed request vanished from the latency histogram:\n" + exposition
        );
    }

    private String scrape() throws Exception {
        MvcResult result = mvc.perform(get("/metrics")).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        return result.getResponse().getContentAsString();
    }
}
