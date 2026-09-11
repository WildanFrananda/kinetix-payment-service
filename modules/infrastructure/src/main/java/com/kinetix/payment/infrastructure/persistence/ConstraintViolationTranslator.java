package com.kinetix.payment.infrastructure.persistence;

import com.kinetix.payment.domain.exception.DomainException;
import com.kinetix.payment.domain.exception.DuplicateIdempotencyKeyException;
import com.kinetix.payment.domain.exception.DuplicateOrderNumberException;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;

public final class ConstraintViolationTranslator {
    private static final String IDEMPOTENCY_KEY = "uq_escrow_idempotency_operation_key";
    private static final String ESCROW_ORDER_NUMBER = "UC_ESCROW_HOLDSORDER_NUMBER_COL";
    private static final String TRANSACTION_REFERENCE = "UC_PAYMENT_TRANSACTIONSREFERENCE_NUMBER_COL";

    private ConstraintViolationTranslator() {}

    public static RuntimeException translate(DataIntegrityViolationException violation) {
        String constraint = constraintOf(violation);
        if (constraint == null) {
            return violation;
        }
        if (constraint.equalsIgnoreCase(IDEMPOTENCY_KEY)) {
            return new DuplicateIdempotencyKeyException(
                "this idempotency key has already been recorded for this operation"
            );
        }
        if (constraint.equalsIgnoreCase(ESCROW_ORDER_NUMBER)) {
            return new DuplicateOrderNumberException("an escrow hold already exists for this order");
        }
        if (constraint.equalsIgnoreCase(TRANSACTION_REFERENCE)) {
            return new DomainException("a payment transaction already records this escrow movement");
        }
        return violation;
    }

    private static String constraintOf(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgres && postgres.getServerErrorMessage() != null) {
                return postgres.getServerErrorMessage().getConstraint();
            }
            if (cause.getCause() == cause) {
                return null;
            }
        }
        return null;
    }
}
