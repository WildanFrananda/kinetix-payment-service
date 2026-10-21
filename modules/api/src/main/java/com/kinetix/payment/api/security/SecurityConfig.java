package com.kinetix.payment.api.security;

import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JsonMapper json) throws Exception {
        AuthenticationRefusals refusals = new AuthenticationRefusals(json);

        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(
                routes -> routes
                    .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .requestMatchers("/health", "/health/**").permitAll()
                    .requestMatchers("/metrics").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/payment/gateway/midtrans/notifications").permitAll()
                    .anyRequest().authenticated()
            )
            .oauth2ResourceServer(
                oauth2 -> oauth2
                    .jwt(Customizer.withDefaults())
                    .authenticationEntryPoint(refusals)
                    .withObjectPostProcessor(
                        new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override
                            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                filter.setAuthenticationFailureHandler(refusals);
                                return filter;
                            }
                        }
                    )
            )
            .exceptionHandling(handling -> handling.authenticationEntryPoint(refusals))
            .build();
    }

    @Bean
    public JwtDecoder jwtDecoder(
        @Value("${kinetix.jwt.jwks-uri}") String jwksUri,
        @Value("${kinetix.jwt.issuer}") String issuer,
        @Value("${kinetix.jwt.audience}") String audience
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(RateLimitedKeys.fetchedFrom(jwksUri))
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            .build();

        OAuth2TokenValidator<Jwt> validators = new DelegatingOAuth2TokenValidator<>(
            List.of(
                JwtValidators.createDefaultWithIssuer(issuer),
                new JwtClaimValidator<List<String>>(
                    "aud",
                    aud -> aud != null && aud.contains(audience)
                ),
                new TokenUseValidator()
            )
        );
        decoder.setJwtValidator(validators);

        return decoder;
    }
}
