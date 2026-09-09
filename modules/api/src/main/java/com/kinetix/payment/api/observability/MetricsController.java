package com.kinetix.payment.api.observability;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetricsController {
    private static final Logger LOG = LoggerFactory.getLogger(MetricsController.class);

    private static final String TEXT_EXPOSITION = "text/plain; version=0.0.4; charset=utf-8";

    private final PrometheusMeterRegistry registry;

    public MetricsController(PrometheusMeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/metrics")
    public ResponseEntity<String> scrape() {
        String exposition;
        try {
            exposition = registry.scrape();
        } catch (RuntimeException failure) {
            LOG.error("the metric registry could not be scraped", failure);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.TEXT_PLAIN)
                .body("the metric registry could not be scraped\n");
        }

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, TEXT_EXPOSITION)
            .body(exposition);
    }
}
