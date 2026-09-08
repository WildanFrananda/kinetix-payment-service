package com.kinetix.payment.infrastructure.persistence;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.kinetix.payment.infrastructure.persistence")
@EntityScan(basePackages = "com.kinetix.payment.infrastructure.persistence")
@EnableJpaRepositories(basePackages = "com.kinetix.payment.infrastructure.persistence")
public class PersistenceTestApplication {
}
