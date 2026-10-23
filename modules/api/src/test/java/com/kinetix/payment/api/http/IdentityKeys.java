package com.kinetix.payment.api.http;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

final class IdentityKeys {
    static final String ISSUER = "https://identity.probe.invalid";

    static final String AUDIENCE = "kinetix";

    private final RSAKey signing;

    private final HttpServer server;

    private IdentityKeys(RSAKey signing, HttpServer server) {
        this.signing = signing;
        this.server = server;
    }

    static IdentityKeys serving() {
        try {
            RSAKey signing = new RSAKeyGenerator(2048).keyID("probe-key").generate();
            byte[] jwks = new JWKSet(signing.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/.well-known/jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (OutputStream body = exchange.getResponseBody()) {
                    body.write(jwks);
                }
            });

            server.start();

            return new IdentityKeys(signing, server);
        } catch (JOSEException | IOException failure) {
            throw new IllegalStateException("the probe's identity could not start", failure);
        }
    }

    static IdentityKeys unreachable() {
        try {
            return new IdentityKeys(new RSAKeyGenerator(2048).keyID("probe-key").generate(), null);
        } catch (JOSEException failure) {
            throw new IllegalStateException("the probe's signing key could not be generated", failure);
        }
    }

    String jwksUri() {
        if (server == null) {
            return "http://127.0.0.1:1/.well-known/jwks.json";
        }

        return "http://127.0.0.1:" + server.getAddress().getPort() + "/.well-known/jwks.json";
    }

    String tokenFor(String principalId, String role) {
        return signedWith(signing, principalId, role, "access");
    }

    String refreshTokenFor(String principalId, String role) {
        return signedWith(signing, principalId, role, "refresh");
    }

    String tokenWithAnUnknownKey(String principalId, String role) {
        try {
            RSAKey stranger = new RSAKeyGenerator(2048).keyID("unknown-" + UUID.randomUUID()).generate();
            return signedWith(stranger, principalId, role, "access");
        } catch (JOSEException failure) {
            throw new IllegalStateException("a stranger's key could not be generated", failure);
        }
    }

    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String signedWith(RSAKey key, String principalId, String role, String tokenUse) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject(principalId)
            .issuer(ISSUER)
            .audience(AUDIENCE)
            .expirationTime(Date.from(Instant.now().plusSeconds(900)))
            .claim("token_use", tokenUse)
            .claim("email", "probe@kinetix.test")
            .claim("role", role)
            .build();
        SignedJWT token = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            claims
        );
        try {
            token.sign(new RSASSASigner(key));
        } catch (JOSEException failure) {
            throw new IllegalStateException("the probe token could not be signed", failure);
        }

        return token.serialize();
    }
}
