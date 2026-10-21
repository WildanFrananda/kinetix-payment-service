package com.kinetix.payment.api.security;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSetCacheRefreshEvaluator;
import com.nimbusds.jose.jwk.source.JWKSetSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.IOException;

final class ObservedKeySetSource implements JWKSetSource<SecurityContext> {
    private final JWKSetSource<SecurityContext> source;

    private volatile boolean lastFetchFailed;

    ObservedKeySetSource(JWKSetSource<SecurityContext> source) {
        this.source = source;
    }

    @Override
    public JWKSet getJWKSet(
        JWKSetCacheRefreshEvaluator refreshEvaluator,
        long currentTime,
        SecurityContext context
    ) throws KeySourceException {
        try {
            JWKSet keys = source.getJWKSet(refreshEvaluator, currentTime, context);
            lastFetchFailed = false;
            return keys;
        } catch (KeySourceException failure) {
            lastFetchFailed = true;
            throw failure;
        }
    }

    boolean lastFetchFailed() {
        return lastFetchFailed;
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}
