package com.kinetix.payment.api.http;

import com.kinetix.payment.api.config.GlobalExceptionHandler;
import com.kinetix.payment.api.config.JsonConfiguration;
import com.kinetix.payment.api.controller.ErrorResponseController;
import com.kinetix.payment.api.controller.MidtransNotificationController;
import com.kinetix.payment.api.controller.WalletController;
import com.kinetix.payment.api.observability.HttpRequestMetricsFilter;
import com.kinetix.payment.api.observability.MetricsConfiguration;
import com.kinetix.payment.api.observability.RequestIdFilter;
import com.kinetix.payment.api.security.SecurityConfig;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration(
    excludeName = {
        "org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
        "org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration",
        "org.springframework.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration",
        "org.springframework.boot.grpc.server.autoconfigure.GrpcServerServicesAutoConfiguration",
        "org.springframework.boot.grpc.server.autoconfigure.health.GrpcServerHealthAutoConfiguration",
        "org.springframework.boot.grpc.server.autoconfigure.health.GrpcServerHealthSchedulerAutoConfiguration"
    }
)
@Import(
    {
        SecurityConfig.class,
        GlobalExceptionHandler.class,
        JsonConfiguration.class,
        MetricsConfiguration.class,
        RequestIdFilter.class,
        HttpRequestMetricsFilter.class,
        WalletController.class,
        MidtransNotificationController.class,
        ErrorResponseController.class
    }
)
public class HttpFailureProbeApplication {}
