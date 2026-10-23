package com.kinetix.payment.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import tools.jackson.databind.json.JsonMapper;

public final class AuthenticationRefusals implements AuthenticationEntryPoint, AuthenticationFailureHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationRefusals.class);

    private static final Set<String> BEARER_ERRORS = Set.of(
        OAuth2ErrorCodes.INVALID_REQUEST,
        OAuth2ErrorCodes.INVALID_TOKEN,
        OAuth2ErrorCodes.INSUFFICIENT_SCOPE
    );

    private final JsonMapper json;

    public AuthenticationRefusals(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void commence(
        HttpServletRequest request,
        HttpServletResponse response,
        AuthenticationException refused
    ) throws IOException {
        response.setStatus(statusOf(refused));
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challengeFor(refused));
        write(response, "a valid access token from identity is required");
    }

    @Override
    public void onAuthenticationFailure(
        HttpServletRequest request,
        HttpServletResponse response,
        AuthenticationException failure
    ) throws IOException {
        if (failure instanceof AuthenticationServiceException) {
            LOG.warn(
                "a bearer token could not be checked because identity's keys could not be read: {}",
                failure.getMessage()
            );
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            write(
                response,
                "identity's signing keys could not be read, so the token could not be checked; try again"
            );
            return;
        }

        commence(request, response, failure);
    }

    private static int statusOf(AuthenticationException refused) {
        if (errorOf(refused) instanceof BearerTokenError bearer) {
            return bearer.getHttpStatus().value();
        }

        return HttpServletResponse.SC_UNAUTHORIZED;
    }

    private static String challengeFor(AuthenticationException refused) {
        OAuth2Error error = errorOf(refused);
        if (error != null && BEARER_ERRORS.contains(error.getErrorCode())) {
            return "Bearer error=\"" + error.getErrorCode() + "\"";
        }

        return "Bearer";
    }

    private static OAuth2Error errorOf(AuthenticationException refused) {
        return refused instanceof OAuth2AuthenticationException oauth ? oauth.getError() : null;
    }

    private void write(HttpServletResponse response, String message) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(json.writeValueAsString(Map.of("error", message)));
    }
}
