package com.kinetix.payment.api.security;

import org.springframework.security.oauth2.jwt.Jwt;

public record AccessClaims(String principalId, String email, String role) {

    public static final String CUSTOMER = "customer";
    public static final String SELLER = "seller";
    public static final String COURIER = "courier";
    public static final String ADMIN = "admin";

    public static AccessClaims of(Jwt jwt) {
        return new AccessClaims(
            text(jwt, "sub"),
            text(jwt, "email"),
            text(jwt, "role")
        );
    }

    public boolean mayActOn(String walletRole, String ownerPrincipalId) {
        if (ADMIN.equals(role)) {
            return true;
        }
        return role.equals(walletRole)
            && principalId != null
            && principalId.equals(ownerPrincipalId);
    }

    private static String text(Jwt jwt, String name) {
        Object value = jwt.getClaim(name);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new MalformedTokenException("claim '" + name + "' is missing or not a string");
        }
        return string;
    }

}
