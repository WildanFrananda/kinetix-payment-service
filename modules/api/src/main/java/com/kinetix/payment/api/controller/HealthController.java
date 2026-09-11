package com.kinetix.payment.api.controller;

import com.kinetix.payment.api.lifecycle.ShutdownState;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/health")
public class HealthController {
    private final DataSource dataSource;

    private final ShutdownState shutdownState;

    public HealthController(DataSource dataSource, ShutdownState shutdownState) {
        this.dataSource = dataSource;
        this.shutdownState = shutdownState;
    }

    @GetMapping
    public Map<String, String> live() {
        return Map.of("status", "ok", "service", "kinetix-payment-service");
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, String>> ready() {
        if (shutdownState.isDraining()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "draining"));
        }

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()
        ) {
            statement.execute("SELECT 1");
        } catch (Exception exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "unavailable", "database", "unreachable"));
        }

        return ResponseEntity.ok(Map.of("status", "ok", "database", "reachable"));
    }
}
