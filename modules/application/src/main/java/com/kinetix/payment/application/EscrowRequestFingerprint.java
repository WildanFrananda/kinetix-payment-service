package com.kinetix.payment.application;

import com.kinetix.payment.domain.entity.EscrowHold;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class EscrowRequestFingerprint {
    private static final BigDecimal MINOR_PER_MAJOR = new BigDecimal("100");

    private static final String DEFAULT_CURRENCY = "IDR";

    private EscrowRequestFingerprint() {}

    public static String forCreateHold(
        String orderNumber,
        String customerPrincipalId,
        String merchantPrincipalId,
        String driverPrincipalId,
        long totalMinor,
        long merchantMinor,
        long shippingMinor,
        String currency
    ) {
        return digest(String.join("\n",
            "CREATE_HOLD",
            text(orderNumber),
            text(customerPrincipalId),
            text(merchantPrincipalId),
            text(driverPrincipalId),
            Long.toString(totalMinor),
            Long.toString(merchantMinor),
            Long.toString(shippingMinor),
            currency(currency)
        ));
    }

    public static String forCreateHold(
        String orderNumber,
        String customerPrincipalId,
        String merchantPrincipalId,
        String driverPrincipalId,
        BigDecimal totalOrderAmount,
        BigDecimal merchantAmount,
        BigDecimal shippingFeeAmount
    ) {
        return forCreateHold(
            orderNumber,
            customerPrincipalId,
            merchantPrincipalId,
            driverPrincipalId,
            toMinorUnits(totalOrderAmount),
            toMinorUnits(merchantAmount),
            toMinorUnits(shippingFeeAmount),
            DEFAULT_CURRENCY
        );
    }

    public static String forCreateHold(EscrowHold hold) {
        return forCreateHold(
            hold.orderNumber(),
            hold.customerPrincipalId(),
            hold.merchantPrincipalId(),
            hold.driverPrincipalId(),
            hold.totalOrderAmount(),
            hold.merchantAmount(),
            hold.shippingFeeAmount()
        );
    }

    public static String forRelease(String orderNumber) {
        return digest(String.join("\n", "RELEASE", text(orderNumber)));
    }

    public static String forRefund(String orderNumber) {
        return digest(String.join("\n", "REFUND", text(orderNumber)));
    }

    public static long toMinorUnits(BigDecimal majorUnits) {
        return (majorUnits == null ? BigDecimal.ZERO : majorUnits)
            .multiply(MINOR_PER_MAJOR)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String currency(String value) {
        return value == null || value.isBlank() ? DEFAULT_CURRENCY : value;
    }

    private static String digest(String canonical) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable on this platform", impossible);
        }
    }
}
