package com.kinetix.payment.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class RateLimitedKeysTest {
    private static final String ISSUER = "https://identity.test";
    private static final String AUDIENCE = "kinetix";
    private static final String ALICE = "9f1d4a3e-1c62-4d0a-9a7b-2f5c8e0b41d7";

    private final AtomicInteger fetches = new AtomicInteger();
    private HttpServer identity;
    private RSAKey signing;
    private JwtDecoder decoder;

    @BeforeEach
    void start() throws JOSEException, IOException {
        signing = new RSAKeyGenerator(2048).keyID("k1").generate();
        byte[] jwks = new JWKSet(signing.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);

        identity = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        identity.createContext("/.well-known/jwks.json", exchange -> {
            fetches.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(jwks);
            }
        });
        identity.start();

        String jwksUri = "http://127.0.0.1:" + identity.getAddress().getPort() + "/.well-known/jwks.json";
        decoder = new SecurityConfig().jwtDecoder(jwksUri, ISSUER, AUDIENCE);
    }

    @AfterEach
    void stop() {
        identity.stop(0);
    }

    private static String signed(RSAKey key) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject(ALICE)
            .issuer(ISSUER)
            .audience(AUDIENCE)
            .expirationTime(Date.from(Instant.now().plusSeconds(900)))
            .claim("token_use", "access")
            .build();
        SignedJWT token = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            claims
        );
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }

    @Test
    @DisplayName("a known key is answered without asking identity again")
    void aKnownKeyIsAnsweredWithoutAskingIdentityAgain() throws JOSEException {
        decoder.decode(signed(signing));
        decoder.decode(signed(signing));

        assertEquals(1, fetches.get());
    }

    @Test
    @DisplayName("unknown keys do not make identity answer every request, and are a bad token, not an error")
    void unknownKeysDoNotMakeIdentityAnswerEveryRequest() throws JOSEException {
        RSAKey stranger = new RSAKeyGenerator(2048).generate();
        decoder.decode(signed(signing));

        for (int i = 0; i < 50; i++) {
            String forged = signed(new RSAKey.Builder(stranger).keyID("forged-" + i).build());
            assertThrows(BadJwtException.class, () -> decoder.decode(forged));
        }

        assertTrue(fetches.get() <= 2, "identity was asked " + fetches.get() + " times");
    }
}
