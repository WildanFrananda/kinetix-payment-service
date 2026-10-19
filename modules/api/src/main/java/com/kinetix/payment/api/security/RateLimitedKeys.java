package com.kinetix.payment.api.security;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import java.util.List;

public final class RateLimitedKeys implements JWKSource<SecurityContext> {
    public static final Duration UNKNOWN_KEY_REFRESH = Duration.ofSeconds(10);

    private final JWKSource<SecurityContext> keys;

    private RateLimitedKeys(JWKSource<SecurityContext> keys) {
        this.keys = keys;
    }

    public static RateLimitedKeys fetchedFrom(String jwksUri) {
        try {
            return new RateLimitedKeys(
                JWKSourceBuilder.<SecurityContext>create(
                        URI.create(jwksUri).toURL(),
                        new DefaultResourceRetriever(2_000, 5_000, 51_200)
                    )
                    .rateLimited(UNKNOWN_KEY_REFRESH.toMillis())
                    .build()
            );
        } catch (MalformedURLException invalid) {
            throw new IllegalStateException("the JWKS URI is not a URL: " + jwksUri, invalid);
        }
    }

    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext context) throws KeySourceException {
        try {
            return keys.get(selector, context);
        } catch (RateLimitReachedException limited) {
            return List.of();
        }
    }
}
