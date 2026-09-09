package com.kinetix.payment.api.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MetricsExpositionTest {
    private static final String ORDER_ID = "8f3a1c2e-4b5d-4a6f-9c8b-1d2e3f4a5b6c";

    private PrometheusMeterRegistry registry;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        registry = new MetricsConfiguration()
            .prometheusMeterRegistry("kinetix-payment-service", Optional.empty());

        mvc = MockMvcBuilders
            .standaloneSetup(new RouteTemplateProbeController(), new MetricsController(registry))
            .addFilters(new HttpRequestMetricsFilter(registry))
            .build();
    }

    @Test
    void routeLabelIsTheTemplateAndNotTheIdInThePath() throws Exception {
        mvc.perform(get("/api/v1/probe/" + ORDER_ID)).andReturn();

        String exposition = scrape();

        assertTrue(
            exposition.contains("route=\"/api/v1/probe/{id}\""),
            "the matched pattern should be the route label:\n" + exposition
        );
        assertFalse(
            exposition.contains(ORDER_ID),
            "an id reached a metric label, which is one series per order forever:\n" + exposition
        );
    }

    @Test
    void servesPrometheusTextWithTheNamesTheContractGives() throws Exception {
        mvc.perform(get("/api/v1/probe/" + ORDER_ID)).andReturn();

        String exposition = scrape();

        assertTrue(exposition.contains("\n# HELP ") || exposition.startsWith("# HELP "),
            "not Prometheus text exposition:\n" + exposition);

        for (String required : new String[] {
            "kinetix_http_requests_total{",
            "kinetix_http_request_duration_seconds_bucket{",
            "kinetix_http_request_duration_seconds_sum{",
            "kinetix_http_request_duration_seconds_count{",
            "kinetix_build_info{"
        }) {
            assertTrue(exposition.contains(required),
                required + " is missing from:\n" + exposition);
        }
    }

    @Test
    void buildInfoNamesTheServiceAndAVersionEvenWithNoBuildProperties() throws Exception {
        String exposition = scrape();

        assertTrue(exposition.contains("service=\"kinetix-payment-service\""), exposition);
        assertTrue(exposition.contains("version=\"unknown\""), exposition);
    }

    @Test
    void countsARefusedRequestUnderABoundedRouteRatherThanTheRawPath() throws Exception {
        mvc.perform(get("/nothing/is/mapped/here/" + ORDER_ID)).andReturn();

        String exposition = scrape();

        assertFalse(exposition.contains("/nothing/is/mapped/here"),
            "an unmatched path became its own series:\n" + exposition
        );
        assertTrue(exposition.contains("route=\"UNKNOWN\""), exposition);
    }

    private String scrape() throws Exception {
        MvcResult result = mvc.perform(get("/metrics")).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(
            "text/plain; version=0.0.4; charset=utf-8",
            result.getResponse().getHeader("Content-Type")
        );
        return result.getResponse().getContentAsString();
    }
}
