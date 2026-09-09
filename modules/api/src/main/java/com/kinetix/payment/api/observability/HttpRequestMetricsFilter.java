package com.kinetix.payment.api.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class HttpRequestMetricsFilter extends OncePerRequestFilter {
    static final String REQUESTS = "kinetix.http.requests";

    static final String REQUESTS_HELP =
        "HTTP requests this service has answered, by route and status.";

    private static final String DURATION = "kinetix.http.request.duration";

    private static final Duration[] BUCKETS = {
        Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
        Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500),
        Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10)
    };

    private static final Set<String> METHODS = Set.of(
        "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT"
    );

    private static final String NO_ROUTE = "UNKNOWN";

    private final MeterRegistry registry;

    public HttpRequestMetricsFilter(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            record(request, response, System.nanoTime() - startedAt);
        }
    }

    private void record(HttpServletRequest request, HttpServletResponse response, long elapsed) {
        String method = methodOf(request);
        String route = routeOf(request);

        Timer.builder(DURATION)
            .description("How long this service took to answer an HTTP request.")
            .serviceLevelObjectives(BUCKETS)
            .tag("method", method)
            .tag("route", route)
            .register(registry)
            .record(elapsed, TimeUnit.NANOSECONDS);

        Counter.builder(REQUESTS)
            .description(REQUESTS_HELP)
            .tag("method", method)
            .tag("route", route)
            .tag("status", Integer.toString(response.getStatus()))
            .register(registry)
            .increment();
    }

    private static String methodOf(HttpServletRequest request) {
        String method = request.getMethod();
        return method != null && METHODS.contains(method) ? method : NO_ROUTE;
    }

    private static String routeOf(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern == null ? NO_ROUTE : pattern.toString();
    }
}
