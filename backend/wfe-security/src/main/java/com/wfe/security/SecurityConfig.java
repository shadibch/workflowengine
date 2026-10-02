package com.wfe.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Clock;
import java.util.List;

/**
 * The API's security posture: stateless, bearer-token only, everything
 * authenticated by default.
 *
 * <h2>Decisions worth stating</h2>
 * <ul>
 *   <li><b>CSRF disabled.</b> There is no cookie to forge a request with: the
 *       browser never attaches credentials automatically, and every call carries an
 *       {@code Authorization} header. Leaving CSRF on would produce a token the
 *       client cannot supply.</li>
 *   <li><b>No sessions.</b> Authorization state is re-derived from the database per
 *       request, so a server-side session would only add a place for a stale
 *       decision to hide.</li>
 *   <li><b>Deny by default.</b> Only the liveness endpoints are public. A new
 *       controller is authenticated until someone deliberately opens it, which is
 *       the right default for an engine whose endpoints start and cancel business
 *       processes.</li>
 *   <li><b>{@code @EnableMethodSecurity}.</b> Object-level rules (may this user
 *       claim <em>this</em> task) are enforced in the service layer, because only
 *       there is the resource loaded.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
@ComponentScan(basePackages = "com.wfe.security")
public class SecurityConfig {

    /** Paths reachable without a token. Kept minimal and side-effect free. */
    private static final String[] PUBLIC_PATHS = {
            "/actuator/health/**",
            "/actuator/info",
            "/v3/api-docs/**",
            "/swagger-ui/**"
    };

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                               DbJwtAuthenticationConverter jwtConverter,
                                               CorsConfigurationSource corsConfigurationSource)
            throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint((request, response, exception) -> {
                            // No detail in the body: an error message that says
                            // "unknown account" or "suspended" is a user
                            // enumeration oracle.
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setHeader("Content-Type", "application/problem+json");
                            response.getWriter().printf(
                                    "{\"type\":\"about:blank\",\"title\":\"Unauthorized\","
                                            + "\"status\":401,\"detail\":\"Authentication is required\"}");
                        }))
                .exceptionHandling(handling -> handling.accessDeniedHandler((request, response, ex) -> {
                    response.setStatus(HttpStatus.FORBIDDEN.value());
                    response.setHeader("Content-Type", "application/problem+json");
                    response.getWriter().printf(
                            "{\"type\":\"about:blank\",\"title\":\"Forbidden\","
                                    + "\"status\":403,\"detail\":\"Insufficient permissions\"}");
                }))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000L)));

        return http.build();
    }

    /**
     * CORS for the SPA.
     *
     * <p>Origins are listed explicitly, never {@code *}: the API is
     * credential-bearing and a wildcard origin with credentials is rejected by
     * browsers, so a wildcard would only produce a confusing failure while looking
     * permissive.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With", "X-WFE-Tenant"));
        configuration.setExposedHeaders(List.of("Location", "X-WFE-Request-Id"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    /**
     * The single {@link Clock} for the whole application.
     *
     * <p>Injected everywhere rather than calling {@code Instant.now()} inline, so
     * a timer, an SLA and a validity window can be tested against a fixed
     * instant — and so a future change of timezone handling is one bean rather than
     * a search across the codebase.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Decodes the OIDC access token against the Keycloak realm's JWKS.
     *
     * <p>The JWKS endpoint is derived from the issuer and the key set is fetched
     * lazily by {@code NimbusJwtDecoder} on the first decode, then cached — a cold
     * Keycloak therefore does not prevent the API from starting, and the token
     * still gets checked against the realm's signing keys. HSTS below applies to
     * the API endpoints reachable from a browser; the JWKS call originates
     * server-side over plain HTTP in local mode only.
     */
    @Bean
    JwtDecoder jwtDecoder(SecurityProperties properties) {
        return NimbusJwtDecoder.withJwkSetUri(
                properties.issuerUri() + "/protocol/openid-connect/certs")
                .build();
    }
}
