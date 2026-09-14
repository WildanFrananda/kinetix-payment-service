package com.kinetix.payment.api.config;

import com.kinetix.payment.api.security.ForbiddenException;
import com.kinetix.payment.api.security.MalformedTokenException;
import com.kinetix.payment.domain.exception.DomainException;
import com.kinetix.payment.domain.exception.EscrowNotFoundException;
import com.kinetix.payment.domain.exception.GatewayRefusedException;
import com.kinetix.payment.domain.exception.GatewayUnavailableException;
import com.kinetix.payment.domain.exception.IdempotencyConflictException;
import com.kinetix.payment.domain.exception.InsufficientBalanceException;
import com.kinetix.payment.domain.exception.NotificationRejectedException;
import com.kinetix.payment.domain.exception.SettlementMismatchException;
import com.kinetix.payment.domain.exception.TopUpNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientBalance(InsufficientBalanceException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(EscrowNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleEscrowNotFound(EscrowNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Map<String, String>> handleDomainException(DomainException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> handleForbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(MalformedTokenException.class)
    public ResponseEntity<Map<String, String>> handleMalformedToken(MalformedTokenException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(GatewayUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleGatewayUnavailable(GatewayUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(withReference(ex.getMessage(), ex.referenceNumber()));
    }

    @ExceptionHandler(GatewayRefusedException.class)
    public ResponseEntity<Map<String, String>> handleGatewayRefused(GatewayRefusedException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(withReference(ex.getMessage(), ex.referenceNumber()));
    }

    @ExceptionHandler(TopUpNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleTopUpNotFound(TopUpNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> handleIdempotencyConflict(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(SettlementMismatchException.class)
    public ResponseEntity<Map<String, String>> handleSettlementMismatch(SettlementMismatchException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(NotificationRejectedException.class)
    public ResponseEntity<Map<String, String>> handleNotificationRejected(NotificationRejectedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", ex.getMessage()));
    }

    private static Map<String, String> withReference(String message, String referenceNumber) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", message);
        if (referenceNumber != null) {
            body.put("referenceNumber", referenceNumber);
        }
        return body;
    }
}
