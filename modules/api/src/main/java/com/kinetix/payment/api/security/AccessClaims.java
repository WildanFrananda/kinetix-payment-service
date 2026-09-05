package com.kinetix.payment.api.security;

import org.springframework.security.oauth2.jwt.Jwt;

public record AccessClaims(String principalId, long userId, String email, String role) {

    public static final String CUSTOMER = "customer";
    public static final String SELLER = "seller";
    public static final String COURIER = "courier";
    public static final String ADMIN = "admin";

    public static AccessClaims of(Jwt jwt) {
        return new AccessClaims(
            text(jwt, "sub"),
            number(jwt, "uid"),
            text(jwt, "email"),
            text(jwt, "role")
        );
    }

    public boolean mayActOn(String walletRole, long ownerId) {
        if (ADMIN.equals(role)) {
            return true;
        }
        return role.equals(walletRole) && userId == ownerId;
    }

    private static String text(Jwt jwt, String name) {
        Object value = jwt.getClaim(name);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new MalformedTokenException("claim '" + name + "' is missing or not a string");
        }
        return string;
    }

    private static long number(Jwt jwt, String name) {
        Object value = jwt.getClaim(name);
        if (!(value instanceof Long || value instanceof Integer)) {
            throw new MalformedTokenException("claim '" + name + "' is missing or not an integer");
        }
        return ((Number) value).longValue();
    }
}
