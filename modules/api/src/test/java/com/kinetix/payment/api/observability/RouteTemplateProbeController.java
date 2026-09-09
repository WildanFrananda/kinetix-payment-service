package com.kinetix.payment.api.observability;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RouteTemplateProbeController {
    @GetMapping("/api/v1/probe/{id}")
    public String probe(@PathVariable String id) {
        return id;
    }
}
