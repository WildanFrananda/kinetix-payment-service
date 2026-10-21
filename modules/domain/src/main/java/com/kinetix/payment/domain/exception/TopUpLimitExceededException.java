package com.kinetix.payment.domain.exception;

import java.math.BigDecimal;

public class TopUpLimitExceededException extends DomainException {
    public static final String TOP_UP_AMOUNT = "TOP_UP_AMOUNT_ABOVE_LIMIT";

    public static final String WALLET_BALANCE = "WALLET_BALANCE_LIMIT_EXCEEDED";

    private final String code;

    private final BigDecimal limit;

    public TopUpLimitExceededException(String code, BigDecimal limit, String message) {
        super(message);
        this.code = code;
        this.limit = limit;
    }

    public String code() {
        return code;
    }

    public BigDecimal limit() {
        return limit;
    }
}
