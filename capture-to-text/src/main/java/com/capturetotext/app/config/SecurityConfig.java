package com.capturetotext.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The API is an OAuth2 "resource server": every /api request must carry a JWT access token
 * from Keycloak (Authorization: Bearer ...). Spring checks its signature against Keycloak's
 * public keys, its expiry, issuer and audience, before any controller runs. Nothing here
 * handles passwords or login pages; that's entirely Keycloak's job.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // Stripe can't log in. This endpoint is protected by the webhook signature instead.
                        .requestMatchers("/api/webhooks/stripe").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        // The React app's static files, and /actuator for k8s probes and Prometheus.
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                // No server-side session: each request proves itself with its own token.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CSRF attacks ride on cookies the browser attaches automatically. A bearer token is
                // only sent when our own code adds it, so there is nothing for CSRF to abuse.
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
