package com.kinetix.payment.api.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Component
public class HttpRouteMetricsSeeder {
    private static final String SEEDED_STATUS = "200";

    private final MeterRegistry registry;

    private final ObjectProvider<RequestMappingHandlerMapping> mappings;

    public HttpRouteMetricsSeeder(
        MeterRegistry registry, ObjectProvider<RequestMappingHandlerMapping> mappings
    ) {
        this.registry = registry;
        this.mappings = mappings;
    }

    @EventListener
    public void seedMappedRoutes(ApplicationReadyEvent ready) {
        RequestMappingHandlerMapping mapping = mappings.getIfAvailable();
        if (mapping == null) {
            return;
        }

        for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            if (methods.isEmpty()) {
                continue;
            }

            for (String route : routesOf(info)) {
                for (RequestMethod method : methods) {
                    seed(method.name(), route);
                }
            }
        }
    }

    private static Set<String> routesOf(RequestMappingInfo info) {
        PathPatternsRequestCondition patterns = info.getPathPatternsCondition();
        return patterns == null ? Set.of() : patterns.getPatternValues();
    }

    private void seed(String method, String route) {
        Counter.builder(HttpRequestMetricsFilter.REQUESTS)
            .description(HttpRequestMetricsFilter.REQUESTS_HELP)
            .tag("method", method)
            .tag("route", route)
            .tag("status", SEEDED_STATUS)
            .register(registry);
    }
}
