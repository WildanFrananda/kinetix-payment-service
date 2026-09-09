package com.kinetix.payment.api.observability;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SeededRoutesProbeController {
    @GetMapping("/api/v1/seed/{id}")
    public String read(@PathVariable String id) {
        return id;
    }

    @PostMapping("/api/v1/seed")
    @ResponseStatus(HttpStatus.CREATED)
    public String create() {
        return "created";
    }
}
