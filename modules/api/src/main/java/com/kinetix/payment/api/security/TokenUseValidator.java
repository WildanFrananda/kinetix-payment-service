package com.kinetix.payment.api.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public final class TokenUseValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error NOT_AN_ACCESS_TOKEN = new OAuth2Error(
        "invalid_token", "the token is not an access token", null);

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        return "access".equals(token.getClaimAsString("token_use"))
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(NOT_AN_ACCESS_TOKEN);
    }
}
