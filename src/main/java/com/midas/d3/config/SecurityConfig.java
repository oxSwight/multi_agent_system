package com.midas.d3.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Spring Security configuration for MIDAS_D3.
 *
 * <h2>Security model</h2>
 * <ul>
 *   <li>CSRF disabled — stateless JWT API; no browser session to protect.</li>
 *   <li>Sessions are STATELESS — no {@code JSESSIONID} cookie is created.</li>
 *   <li>Public paths: Swagger UI / OpenAPI docs and the {@code /health} liveness &amp; readiness
 *       probes are reachable without a token (the probes expose only an UP/DOWN status).</li>
 *   <li>Protected paths: the Pipeline REST API ({@code /api/v1/pipelines/**}) and all
 *       {@code /api/v1/dashboard/**} endpoints require a valid
 *       {@code Authorization: Bearer <jwt>} header. The pipeline API exposes
 *       destructive ({@code DELETE}) and full-context-read operations, so it must
 *       never be anonymously accessible.</li>
 *   <li>CORS: allowed origins are configurable via {@code midas.security.cors.allowed-origins}
 *       (env {@code MIDAS_CORS_ALLOWED_ORIGINS}), defaulting to {@code http://localhost:3000} for
 *       Next.js dev, applied to all {@code /api/**} paths.</li>
 * </ul>
 *
 * <h2>Error responses</h2>
 * 401 and 403 are returned as JSON objects consistent with
 * {@link com.midas.d3.api.GlobalExceptionHandler}'s envelope format.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final ObjectMapper            objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm ->
                        sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeHttpRequests(auth -> auth
                        // Swagger / OpenAPI
                        .requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html")
                        .permitAll()
                        // Health / readiness probes — public so orchestrators & load balancers can
                        // reach them without a token. They expose no data (status UP/DOWN only).
                        .requestMatchers("/health", "/health/**")
                        .permitAll()
                        // Pipeline API — JWT-protected (exposes DELETE + full-context read)
                        .requestMatchers("/api/v1/pipelines/**").authenticated()
                        // Dashboard API — JWT-protected
                        .requestMatchers("/api/v1/dashboard/**").authenticated()
                        // Everything else requires authentication by default
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "Unauthorized", "Authentication required — provide a valid Bearer token"))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeErrorResponse(response, HttpServletResponse.SC_FORBIDDEN,
                                        "Forbidden", "You do not have permission to access this resource"))
                )
                .build();
    }

    /**
     * Prevents Spring Boot from auto-registering {@link JwtAuthenticationFilter} as a
     * standalone servlet filter. Because it is a {@code @Component} that implements
     * {@link jakarta.servlet.Filter}, Boot would otherwise register it twice: once
     * inside the Spring Security chain (via {@code addFilterBefore}, where the request
     * {@link org.springframework.security.core.context.SecurityContext} is cleared after
     * every request) and once as a top-level servlet filter that runs <em>after</em> the
     * security chain has already cleared the context. The standalone copy would set an
     * authentication into the thread-local {@code SecurityContextHolder} and never clear
     * it, leaking the caller's identity onto the next request served by the same pooled
     * Tomcat thread — a critical authentication-bypass bug. Disabling the registration
     * keeps the filter exclusively inside the security chain.
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @org.springframework.beans.factory.annotation.Value(
                    "${midas.security.cors.allowed-origins:http://localhost:3000}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        // Externalized so prod can set its real origin(s) via MIDAS_CORS_ALLOWED_ORIGINS without a code change.
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    // ── Error response helpers ────────────────────────────────────────────────

    private void writeErrorResponse(HttpServletResponse response,
                                    int status,
                                    String error,
                                    String message) {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        try {
            Map<String, Object> body = Map.of(
                    "status",    status,
                    "error",     error,
                    "message",   message,
                    "timestamp", Instant.now().toString()
            );
            response.getWriter().write(objectMapper.writeValueAsString(body));
        } catch (Exception ignored) {
            // If serialisation fails the status code is already set — that's enough.
        }
    }
}
