package com.midas.d3.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

/**
 * Lightweight, dependency-free health endpoints for container / orchestrator / load-balancer probes.
 *
 * <ul>
 *   <li>{@code GET /health} — liveness: the JVM is up and the web layer is serving. Always 200 while
 *       the process runs. Used to detect a hung/dead container.</li>
 *   <li>{@code GET /health/ready} — readiness: additionally verifies the database is reachable, so a
 *       load balancer / docker-compose healthcheck does not route traffic to an instance whose DB
 *       connection is down. Returns 503 when the DB is unreachable.</li>
 * </ul>
 *
 * <p><b>Why not Spring Boot Actuator?</b> The offline build pins Spring Boot 3.3.0 and
 * {@code spring-boot-starter-actuator} is not in the local Maven cache, so it cannot be added under the
 * {@code mvnw -o} constraint. This controller is a deliberate, minimal stand-in with the same probe
 * semantics; it can be swapped for actuator liveness/readiness probe groups once dependencies can be
 * fetched online. Both paths are unauthenticated (see {@code SecurityConfig}) — they expose no data.
 */
@Slf4j
@RestController
@RequestMapping("/health")
@RequiredArgsConstructor
public class HealthController {

    /** Timeout (seconds) for the readiness DB validity check — short so a probe never blocks. */
    private static final int DB_VALIDATION_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    /** Liveness probe: 200 while the process is up and serving. */
    @GetMapping
    public ResponseEntity<Map<String, String>> live() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    /** Readiness probe: 200 only when the database is reachable; 503 otherwise. */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, String>> ready() {
        if (isDatabaseReachable()) {
            return ResponseEntity.ok(Map.of("status", "UP", "db", "UP"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "DOWN", "db", "DOWN"));
    }

    private boolean isDatabaseReachable() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(DB_VALIDATION_TIMEOUT_SECONDS);
        } catch (Exception e) {
            log.warn("[HealthController] Readiness DB check failed: {}", e.getMessage());
            return false;
        }
    }
}
