package com.kinetix.payment.api.observability;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Component
public class HttpRouteMetricsSeeder {
    private static final String OK = Integer.toString(HttpStatus.OK.value());

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

        for (Map.Entry<RequestMappingInfo, HandlerMethod> mapped
            : mapping.getHandlerMethods().entrySet()
        ) {
            RequestMappingInfo info = mapped.getKey();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            if (methods.isEmpty()) {
                continue;
            }

            String status = declaredStatusOf(mapped.getValue());
            for (String route : routesOf(info)) {
                for (RequestMethod method : methods) {
                    seed(method.name(), route, status);
                }
            }
        }
    }

    private static Set<String> routesOf(RequestMappingInfo info) {
        PathPatternsRequestCondition patterns = info.getPathPatternsCondition();
        return patterns == null ? Set.of() : patterns.getPatternValues();
    }

    private static String declaredStatusOf(HandlerMethod handler) {
        ResponseStatus declared =
            AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), ResponseStatus.class);
        if (declared == null) {
            declared = AnnotatedElementUtils.findMergedAnnotation(
                handler.getBeanType(), ResponseStatus.class
            );
        }
        return declared == null ? OK : Integer.toString(declared.code().value());
    }

    private void seed(String method, String route, String status) {
        HttpRequestMetricsFilter.requests(registry, method, route, status);
        HttpRequestMetricsFilter.duration(registry, method, route);
    }
}
