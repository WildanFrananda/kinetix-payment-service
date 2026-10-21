package com.kinetix.payment.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import tools.jackson.databind.json.JsonMapper;

public final class AuthenticationRefusals implements AuthenticationEntryPoint, AuthenticationFailureHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationRefusals.class);

    private final BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();

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
        bearer.commence(request, response, refused);
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

    private void write(HttpServletResponse response, String message) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(json.writeValueAsString(Map.of("error", message)));
    }
}
