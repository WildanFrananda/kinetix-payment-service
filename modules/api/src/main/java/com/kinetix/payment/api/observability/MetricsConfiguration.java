package com.kinetix.payment.api.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfiguration {
    private static final String BUILD_INFO = "kinetix.build.info";

    private static final String UNKNOWN_VERSION = "unknown";

    @Bean
    public PrometheusMeterRegistry prometheusMeterRegistry(
        @Value("${spring.application.name}") String service,
        Optional<BuildProperties> build
    ) {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        Gauge.builder(BUILD_INFO, () -> 1.0)
            .description("Always 1. Carries the service name and build version as labels.")
            .tag("service", service)
            .tag("version", versionOf(build))
            .strongReference(true)
            .register(registry);

        return registry;
    }

    private static String versionOf(Optional<BuildProperties> build) {
        return build.map(BuildProperties::getVersion).orElse(UNKNOWN_VERSION);
    }
}
