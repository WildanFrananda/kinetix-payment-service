package com.kinetix.payment.api.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ErrorResponseController implements ErrorController {
    @RequestMapping("/error")
    public ResponseEntity<Map<String, String>> error(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = code instanceof Integer value ? HttpStatus.resolve(value) : null;
        if (status == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no such route"));
        }

        String message = status.is5xxServerError()
            ? "the payment service failed to handle this request"
            : status.getReasonPhrase();
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
