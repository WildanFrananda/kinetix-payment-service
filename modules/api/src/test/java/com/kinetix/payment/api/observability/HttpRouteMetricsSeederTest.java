package com.kinetix.payment.api.observability;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kinetix.payment.api.controller.EscrowController;
import com.kinetix.payment.api.controller.PaymentController;
import com.kinetix.payment.api.controller.WalletController;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class HttpRouteMetricsSeederTest {
    @Test
    void aRouteThatOnlyAnswersCreatedIsNotSeededAsAnOkThatCanNeverMove() {
        String exposition = seed(SeededRoutesProbeController.class);

        assertTrue(
            exposition.contains(
                "kinetix_http_requests_total{method=\"POST\",route=\"/api/v1/seed\","
                    + "status=\"201\"} 0.0"
            ),
            "the 201 route was not seeded under the status it declares:\n" + exposition
        );
        assertFalse(
            exposition.contains("route=\"/api/v1/seed\",status=\"200\""),
            "a 200 series was invented for a route that only ever answers 201, so a success-rate "
                + "panel keyed on it reads zero forever:\n" + exposition
        );
    }

    @Test
    void aRouteThatAnswersOkIsStillSeededAsOk() {
        String exposition = seed(SeededRoutesProbeController.class);

        assertTrue(
            exposition.contains(
                "kinetix_http_requests_total{method=\"GET\",route=\"/api/v1/seed/{id}\","
                    + "status=\"200\"} 0.0"
            ),
            exposition
        );
    }

    @Test
    void theHistogramIsServedBeforeTheFirstRequestRatherThanBeingAbsent() {
        String exposition = seed(SeededRoutesProbeController.class);

        for (String required : new String[] {
            "kinetix_http_request_duration_seconds_bucket{method=\"GET\","
                + "route=\"/api/v1/seed/{id}\",le=\"0.005\"} 0",
            "kinetix_http_request_duration_seconds_count{method=\"GET\","
                + "route=\"/api/v1/seed/{id}\"} 0",
            "kinetix_http_request_duration_seconds_sum{method=\"GET\","
                + "route=\"/api/v1/seed/{id}\"} 0.0"
        }) {
            assertTrue(
                exposition.contains(required),
                "a process scraped before its first HTTP request serves no "
                    + "kinetix_http_request_duration_seconds, and the conformance gate goes red "
                    + "on scrape ordering rather than on the service.\nmissing: " + required
                    + "\n" + exposition
            );
        }
    }

    @Test
    void paymentsOwnCreatedRoutesAreSeededAsCreated() {
        String exposition =
            seed(PaymentController.class, WalletController.class, EscrowController.class);

        for (String created : new String[] {
            "kinetix_http_requests_total{method=\"POST\","
                + "route=\"/api/v1/payment/checkout/pay\",status=\"201\"} 0.0",
            "kinetix_http_requests_total{method=\"POST\","
                + "route=\"/api/v1/payment/wallet/customer/topup\",status=\"201\"} 0.0"
        }) {
            assertTrue(exposition.contains(created),
                "missing: " + created + "\n" + exposition
            );
        }

        for (String invented : new String[] {
            "route=\"/api/v1/payment/checkout/pay\",status=\"200\"",
            "route=\"/api/v1/payment/wallet/customer/topup\",status=\"200\""
        }) {
            assertFalse(exposition.contains(invented),
                "payment's two most important success series can never move:\n" + exposition
            );
        }

        assertTrue(
            exposition.contains(
                "kinetix_http_requests_total{method=\"GET\","
                    + "route=\"/api/v1/payment/wallet/customer/balance\",status=\"200\"} 0.0"
            ),
            "a route that really does answer 200 stopped being seeded as 200:\n" + exposition
        );
    }

    private static String seed(Class<?>... controllers) {
        PrometheusMeterRegistry registry = new MetricsConfiguration()
            .prometheusMeterRegistry("kinetix-payment-service", Optional.empty());

        GenericApplicationContext context = new GenericApplicationContext();
        for (Class<?> controller : controllers) {
            context.registerBean(
                controller.getName(), controller, definition -> definition.setLazyInit(true)
            );
        }
        context.refresh();

        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(context);
        mapping.afterPropertiesSet();

        new HttpRouteMetricsSeeder(registry, providerOf(mapping)).seedMappedRoutes(
            new ApplicationReadyEvent(
                new SpringApplication(), new String[0], context, Duration.ZERO
            )
        );

        return registry.scrape();
    }

    private static ObjectProvider<RequestMappingHandlerMapping> providerOf(
        RequestMappingHandlerMapping mapping
    ) {
        return new ObjectProvider<>() {
            @Override
            public RequestMappingHandlerMapping getObject() {
                return mapping;
            }
        };
    }
}
