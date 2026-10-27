package com.kinetix.payment.api.config;

import com.kinetix.payment.api.security.ForbiddenException;
import com.kinetix.payment.api.security.MalformedTokenException;
import com.kinetix.payment.domain.exception.DomainException;
import com.kinetix.payment.domain.exception.EscrowNotFoundException;
import com.kinetix.payment.domain.exception.GatewayRefusedException;
import com.kinetix.payment.domain.exception.GatewayUnavailableException;
import com.kinetix.payment.domain.exception.IdempotencyConflictException;
import com.kinetix.payment.domain.exception.InsufficientBalanceException;
import com.kinetix.payment.domain.exception.InvalidArgumentException;
import com.kinetix.payment.domain.exception.NotificationRejectedException;
import com.kinetix.payment.domain.exception.TopUpLimitExceededException;
import com.kinetix.payment.domain.exception.TopUpNotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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

    @ExceptionHandler(InvalidArgumentException.class)
    public ResponseEntity<Map<String, String>> handleInvalidArgument(InvalidArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        LOG.warn("a request was refused by an argument check payment did not write", ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "the request is not valid"));
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

    @ExceptionHandler(TopUpLimitExceededException.class)
    public ResponseEntity<Map<String, String>> handleTopUpLimitExceeded(TopUpLimitExceededException ex) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", ex.getMessage());
        body.put("code", ex.code());
        body.put("limit", ex.limit().toPlainString());

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(body);
    }

    @ExceptionHandler(NotificationRejectedException.class)
    public ResponseEntity<Map<String, String>> handleNotificationRejected(NotificationRejectedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler({
        CannotCreateTransactionException.class,
        TransactionTimedOutException.class,
        DataAccessResourceFailureException.class,
        TransientDataAccessException.class,
        RecoverableDataAccessException.class
    })
    public ResponseEntity<Map<String, String>> handleLedgerUnavailable(Exception ex) {
        LOG.warn("the payment ledger could not be reached: {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("error", "the payment ledger could not be reached; try again"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
        MethodArgumentNotValidException ex,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        String reasons = ex.getBindingResult().getAllErrors().stream()
            .map(DefaultMessageSourceResolvable::getDefaultMessage)
            .filter(Objects::nonNull)
            .sorted()
            .collect(Collectors.joining("; "));
        String detail = reasons.isBlank() ? "the request body is not valid" : reasons;

        return handleExceptionInternal(ex, ProblemDetail.forStatusAndDetail(status, detail), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
        HttpMessageNotReadableException ex,
        HttpHeaders headers,
        HttpStatusCode status,
        WebRequest request
    ) {
        return handleExceptionInternal(
            ex,
            ProblemDetail.forStatusAndDetail(status, "the request body is not JSON this endpoint can read"),
            headers,
            status,
            request
        );
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
        Object body,
        HttpHeaders headers,
        HttpStatusCode statusCode,
        WebRequest request
    ) {
        return new ResponseEntity<>(Map.of("error", detailOf(body, statusCode)), headers, statusCode);
    }

    private static String detailOf(Object body, HttpStatusCode status) {
        if (body instanceof ProblemDetail problem && problem.getDetail() != null && !problem.getDetail().isBlank()) {
            return problem.getDetail();
        }

        HttpStatus known = HttpStatus.resolve(status.value());
        return known == null ? "HTTP " + status.value() : known.getReasonPhrase();
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
