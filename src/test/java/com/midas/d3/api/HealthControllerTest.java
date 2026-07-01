package com.midas.d3.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("HealthController Tests")
class HealthControllerTest {

    @Mock private DataSource dataSource;
    @Mock private Connection connection;

    private HealthController controller;

    @BeforeEach
    void setUp() {
        controller = new HealthController(dataSource);
    }

    @Test
    @DisplayName("liveness → 200 UP (no DB access)")
    void live_returnsUp() {
        ResponseEntity<Map<String, String>> resp = controller.live();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("status", "UP");
    }

    @Test
    @DisplayName("readiness with reachable DB → 200 UP")
    void ready_dbReachable_returnsUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);

        ResponseEntity<Map<String, String>> resp = controller.ready();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("status", "UP");
        assertThat(resp.getBody()).containsEntry("db", "UP");
    }

    @Test
    @DisplayName("readiness when getConnection throws → 503 DOWN (never crashes the probe)")
    void ready_connectionThrows_returns503() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("no connection"));

        ResponseEntity<Map<String, String>> resp = controller.ready();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resp.getBody()).containsEntry("status", "DOWN");
    }

    @Test
    @DisplayName("readiness when connection is not valid → 503 DOWN")
    void ready_connectionInvalid_returns503() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(false);

        ResponseEntity<Map<String, String>> resp = controller.ready();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resp.getBody()).containsEntry("db", "DOWN");
    }
}
