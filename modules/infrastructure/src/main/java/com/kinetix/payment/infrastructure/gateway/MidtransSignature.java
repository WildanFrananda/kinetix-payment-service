package com.kinetix.payment.infrastructure.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class MidtransSignature {
    private MidtransSignature() {}

    static String of(String orderId, String statusCode, String grossAmount, String serverKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-512")
                .digest((orderId + statusCode + grossAmount + serverKey).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("every Java runtime ships SHA-512", missing);
        }
    }
}
