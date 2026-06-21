package com.midas.d3.web;

import com.midas.d3.config.JacksonConfig;
import com.midas.d3.security.JwtService;
import com.midas.d3.web.dto.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Slice test for {@link DashboardController}.
 *
 * <p>{@code @WebMvcTest} boots only the web layer (DispatcherServlet, filters,
 * {@code GlobalExceptionHandler}) — no JPA, no state machine, no Telegram.
 * {@link DashboardService} is replaced with a Mockito mock via {@code @MockBean}.
 *
 * <p>{@code addFilters = false} disables the Spring Security filter chain so the
 * slice tests can exercise controller logic without providing JWT tokens. Security
 * integration (401/403 responses) is covered by {@link com.midas.d3.api.PipelineControllerIT}.
 *
 * <p>{@link JacksonConfig} is imported explicitly so that {@code Instant} fields
 * are serialised as ISO-8601 strings rather than epoch timestamps.
 */
@WebMvcTest(DashboardController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(JacksonConfig.class)
@DisplayName("DashboardController — @WebMvcTest")
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DashboardService dashboardService;

    /**
     * JwtService is a @Service (not a web-layer bean) but JwtAuthenticationFilter
     * (a @Component Filter) depends on it and IS loaded by @WebMvcTest.
     * Providing a MockBean satisfies that dependency without real JWT logic.
     */
    @MockBean
    @SuppressWarnings("unused")
    private JwtService jwtService;

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static final String  RUN_ID     = "run-abc-123";
    private static final UUID    LOG_UUID   = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant FIXED_NOW  = Instant.parse("2026-06-14T09:00:00Z");

    private static final OverviewMetricsDto OVERVIEW =
            new OverviewMetricsDto(10L, 2000L, 1000L, 1250L, "ImplementationEngineerAgent");

    private static final List<RunItemDto> RUNS = List.of(
            new RunItemDto(RUN_ID, "COMPLETED", "Build a REST API", "/artifacts/run.zip", 100, 50, FIXED_NOW)
    );

    private static final List<AgentLogDto> AGENT_LOGS = List.of(
            new AgentLogDto("SystemAnalystAgent", "{\"business_goal\":\"test\"}", 1400L, 80, 40, "gemini-1.5-flash", false),
            new AgentLogDto("ImplementationEngineerAgent", "{\"code\":\"...\"}", 2600L, 120, 60, "gemini-2.5-flash", false)
    );

    private static final RunDetailsDto DETAILS =
            new RunDetailsDto(RUN_ID, "COMPLETED", "Build a REST API",
                    "/artifacts/run.zip", 100, 50, FIXED_NOW, AGENT_LOGS);

    private static final List<AgentTimeDistributionDto> PERF_STATS = List.of(
            new AgentTimeDistributionDto("ImplementationEngineerAgent", 2600L),
            new AgentTimeDistributionDto("SystemAnalystAgent",    1400L)
    );

    private static final List<TokenUsageTimelineDto> TOKEN_TIMELINE = List.of(
            new TokenUsageTimelineDto(RUN_ID, 100, 50)
    );

    private static final List<EvolutionLogItemDto> EVOLUTION_LOG = List.of(
            new EvolutionLogItemDto(LOG_UUID, RUN_ID, "# Evolution Report\n\nAll good.", FIXED_NOW)
    );

    // ── GET /overview ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/dashboard/overview")
    class GetOverview {

        @Test
        @DisplayName("200 — returns all KPI fields correctly")
        void returnsOverviewDto() throws Exception {
            when(dashboardService.getOverviewMetrics()).thenReturn(OVERVIEW);

            mockMvc.perform(get("/api/v1/dashboard/overview"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalRuns").value(10))
                    .andExpect(jsonPath("$.totalPromptTokens").value(2000))
                    .andExpect(jsonPath("$.totalCompletionTokens").value(1000))
                    .andExpect(jsonPath("$.avgExecutionTimeMs").value(1250))
                    .andExpect(jsonPath("$.mostExpensiveAgent").value("ImplementationEngineerAgent"));
        }

        @Test
        @DisplayName("200 — mostExpensiveAgent is null when no logs exist")
        void nullMostExpensiveAgent_serialisedAsJsonNull() throws Exception {
            when(dashboardService.getOverviewMetrics())
                    .thenReturn(new OverviewMetricsDto(0L, 0L, 0L, 0L, null));

            mockMvc.perform(get("/api/v1/dashboard/overview"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalRuns").value(0))
                    .andExpect(jsonPath("$.mostExpensiveAgent").doesNotExist());
        }

        @Test
        @DisplayName("500 — propagates unexpected service failure")
        void serviceThrows_returns500() throws Exception {
            when(dashboardService.getOverviewMetrics())
                    .thenThrow(new RuntimeException("DB unavailable"));

            mockMvc.perform(get("/api/v1/dashboard/overview"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.status").value(500))
                    .andExpect(jsonPath("$.error").value("Internal Server Error"));
        }
    }

    // ── GET /runs ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/dashboard/runs")
    class GetRuns {

        @Test
        @DisplayName("200 — returns list with one RunItemDto")
        void returnsRunsList() throws Exception {
            when(dashboardService.getAllRuns()).thenReturn(RUNS);

            mockMvc.perform(get("/api/v1/dashboard/runs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].id").value(RUN_ID))
                    .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                    .andExpect(jsonPath("$[0].rawUserIdea").value("Build a REST API"))
                    .andExpect(jsonPath("$[0].promptTokens").value(100))
                    .andExpect(jsonPath("$[0].completionTokens").value(50));
        }

        @Test
        @DisplayName("200 — empty list when no runs exist")
        void emptyList_whenNoRuns() throws Exception {
            when(dashboardService.getAllRuns()).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/dashboard/runs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    // ── GET /runs/{runId} ────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/dashboard/runs/{runId}")
    class GetRunDetails {

        @Test
        @DisplayName("200 — returns RunDetailsDto with agent logs")
        void found_returnsDetails() throws Exception {
            when(dashboardService.getRunDetails(RUN_ID)).thenReturn(DETAILS);

            mockMvc.perform(get("/api/v1/dashboard/runs/" + RUN_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(RUN_ID))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.agentLogs", hasSize(2)))
                    .andExpect(jsonPath("$.agentLogs[0].agentType").value("SystemAnalystAgent"))
                    .andExpect(jsonPath("$.agentLogs[0].executionTimeMs").value(1400))
                    .andExpect(jsonPath("$.agentLogs[0].isError").value(false))
                    .andExpect(jsonPath("$.agentLogs[1].agentType").value("ImplementationEngineerAgent"));
        }

        @Test
        @DisplayName("404 — RunNotFoundException maps to Not Found")
        void notFound_returns404WithErrorBody() throws Exception {
            when(dashboardService.getRunDetails("unknown-id"))
                    .thenThrow(new RunNotFoundException("Pipeline run not found: unknown-id"));

            mockMvc.perform(get("/api/v1/dashboard/runs/unknown-id"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("Not Found"))
                    .andExpect(jsonPath("$.message").value("Pipeline run not found: unknown-id"));
        }
    }

    // ── GET /performance ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/dashboard/performance")
    class GetPerformance {

        @Test
        @DisplayName("200 — returns list ordered by avg time DESC")
        void returnsPerfStats() throws Exception {
            when(dashboardService.getPerformanceStats()).thenReturn(PERF_STATS);

            mockMvc.perform(get("/api/v1/dashboard/performance"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].agentName").value("ImplementationEngineerAgent"))
                    .andExpect(jsonPath("$[0].avgTimeMs").value(2600))
                    .andExpect(jsonPath("$[1].agentName").value("SystemAnalystAgent"))
                    .andExpect(jsonPath("$[1].avgTimeMs").value(1400));
        }

        @Test
        @DisplayName("200 — empty list when no agent logs exist")
        void emptyList_whenNoLogs() throws Exception {
            when(dashboardService.getPerformanceStats()).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/dashboard/performance"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    // ── GET /token-usage ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/dashboard/token-usage")
    class GetTokenUsage {

        @Test
        @DisplayName("200 — returns token usage timeline")
        void returnsTimeline() throws Exception {
            when(dashboardService.getTokenUsageTimeline()).thenReturn(TOKEN_TIMELINE);

            mockMvc.perform(get("/api/v1/dashboard/token-usage"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].runId").value(RUN_ID))
                    .andExpect(jsonPath("$[0].promptTokens").value(100))
                    .andExpect(jsonPath("$[0].completionTokens").value(50));
        }
    }

    // ── GET /evolution-history ────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/dashboard/evolution-history")
    class GetEvolutionHistory {

        @Test
        @DisplayName("200 — returns list with one EvolutionLogItemDto")
        void returnsEvolutionHistory() throws Exception {
            when(dashboardService.getEvolutionHistory()).thenReturn(EVOLUTION_LOG);

            mockMvc.perform(get("/api/v1/dashboard/evolution-history"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].id").value(LOG_UUID.toString()))
                    .andExpect(jsonPath("$[0].runId").value(RUN_ID))
                    .andExpect(jsonPath("$[0].refactoringReport").value("# Evolution Report\n\nAll good."))
                    .andExpect(jsonPath("$[0].createdAt").value("2026-06-14T09:00:00Z"));
        }

        @Test
        @DisplayName("200 — empty list before any evolution cycles have run")
        void emptyList_whenNoEvolutionCyclesYet() throws Exception {
            when(dashboardService.getEvolutionHistory()).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/dashboard/evolution-history"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    // ── CORS ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CORS — http://localhost:3000")
    class Cors {

        @Test
        @DisplayName("Allowed origin receives Access-Control-Allow-Origin header")
        void allowedOrigin_headerPresent() throws Exception {
            when(dashboardService.getOverviewMetrics()).thenReturn(OVERVIEW);

            mockMvc.perform(get("/api/v1/dashboard/overview")
                            .header("Origin", "http://localhost:3000"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        }

        @Test
        @DisplayName("Preflight OPTIONS returns CORS headers for allowed origin")
        void preflight_returnsAllowHeaders() throws Exception {
            mockMvc.perform(options("/api/v1/dashboard/overview")
                            .header("Origin", "http://localhost:3000")
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        }
    }
}
