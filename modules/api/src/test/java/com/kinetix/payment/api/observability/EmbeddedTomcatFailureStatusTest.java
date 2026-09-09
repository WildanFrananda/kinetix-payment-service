package com.kinetix.payment.api.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmbeddedTomcatFailureStatusTest {
    private PrometheusMeterRegistry registry;

    private Tomcat tomcat;

    private Path baseDir;

    @BeforeEach
    void startTomcat() throws Exception {
        registry = new MetricsConfiguration()
            .prometheusMeterRegistry("kinetix-payment-service", Optional.empty());

        baseDir = Files.createTempDirectory("kinetix-payment-metrics-probe");

        tomcat = new Tomcat();
        tomcat.setPort(0);
        tomcat.setBaseDir(baseDir.toString());

        Context context = tomcat.addContext("", baseDir.toString());
        Tomcat.addServlet(context, "boom", new ThrowingServlet());
        context.addServletMappingDecoded("/boom", "boom");

        FilterDef definition = new FilterDef();
        definition.setFilterName("metrics");
        definition.setFilter(new HttpRequestMetricsFilter(registry));
        context.addFilterDef(definition);

        FilterMap mapping = new FilterMap();
        mapping.setFilterName("metrics");
        mapping.addURLPattern("/*");
        mapping.setDispatcher("REQUEST");
        mapping.setDispatcher("ERROR");
        context.addFilterMap(mapping);

        tomcat.getConnector();
        tomcat.start();
    }

    @AfterEach
    void stopTomcat() throws Exception {
        tomcat.stop();
        tomcat.destroy();
        try (var walk = Files.walk(baseDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Test
    void theCounterCarriesTheSameStatusTheHttpClientReceived() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(
                "http://localhost:" + tomcat.getConnector().getLocalPort() + "/boom"
            )).build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(500, response.statusCode(), "the probe did not actually fail on the wire");

        String exposition = registry.scrape();

        assertTrue(
            exposition.contains("kinetix_http_requests_total{method=\"GET\",route=\"UNKNOWN\","
                + "status=\"500\"} 1.0"),
            "the client received 500 and the counter says otherwise:\n" + exposition
        );
        assertFalse(
            exposition.contains("status=\"200\""),
            "an outage would read as a hundred per cent success:\n" + exposition
        );
        assertTrue(
            exposition.contains("kinetix_http_request_duration_seconds_count{method=\"GET\","
                + "route=\"UNKNOWN\"} 1"),
            "the container's ERROR dispatch was counted a second time, so every failure "
                + "inflates the request rate:\n" + exposition
        );
    }
}
