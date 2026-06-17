package com.midas.d3.web;

import com.midas.d3.web.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller that exposes aggregated analytics and run history to the
 * MIDAS Next.js frontend dashboard.
 *
 * <h2>Endpoints</h2>
 * <pre>
 *  GET /api/v1/dashboard/overview          — high-level KPIs                → 200
 *  GET /api/v1/dashboard/runs              — all runs (newest-first)        → 200
 *  GET /api/v1/dashboard/runs/{runId}      — run detail with agent logs     → 200 / 404
 *  GET /api/v1/dashboard/performance       — per-agent avg execution times  → 200
 *  GET /api/v1/dashboard/token-usage       — per-run token consumption      → 200
 *  GET /api/v1/dashboard/evolution-history — EvolutionAgent changelog       → 200
 * </pre>
 *
 * <p>CORS is configured globally in {@link com.midas.d3.config.SecurityConfig}.
 * All error responses are handled centrally by
 * {@link com.midas.d3.api.GlobalExceptionHandler}.
 * All endpoints require a valid {@code Authorization: Bearer <jwt>} header
 * (enforced by {@link com.midas.d3.config.SecurityConfig}).
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@CrossOrigin(origins = "http://localhost:3000")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    // ── GET /overview ────────────────────────────────────────────────────────

    /**
     * Returns the high-level KPIs for the dashboard overview card.
     *
     * @return aggregated {@link OverviewMetricsDto}; always 200
     */
    @GetMapping("/overview")
    public OverviewMetricsDto getOverview() {
        return dashboardService.getOverviewMetrics();
    }

    // ── GET /runs ────────────────────────────────────────────────────────────

    /**
     * Returns a summary list of all pipeline runs, ordered newest-first.
     *
     * @return list of {@link RunItemDto}; empty array when no runs exist
     */
    @GetMapping("/runs")
    public List<RunItemDto> getRuns() {
        return dashboardService.getAllRuns();
    }

    // ── GET /runs/{runId} ────────────────────────────────────────────────────

    /**
     * Returns the full detail view for a single pipeline run, including the
     * ordered list of agent invocation logs.
     *
     * @param runId the pipeline run identifier
     * @return {@link RunDetailsDto}; 404 when the run does not exist
     */
    @GetMapping("/runs/{runId}")
    public RunDetailsDto getRunDetails(@PathVariable String runId) {
        return dashboardService.getRunDetails(runId);
    }

    // ── GET /performance ─────────────────────────────────────────────────────

    /**
     * Returns per-agent average execution times for the performance chart.
     *
     * @return list of {@link AgentTimeDistributionDto} ordered by avg time DESC
     */
    @GetMapping("/performance")
    public List<AgentTimeDistributionDto> getPerformance() {
        return dashboardService.getPerformanceStats();
    }

    // ── GET /token-usage ─────────────────────────────────────────────────────

    /**
     * Returns per-run token consumption data points for the token-usage line chart.
     *
     * @return list of {@link TokenUsageTimelineDto} ordered newest-first
     */
    @GetMapping("/token-usage")
    public List<TokenUsageTimelineDto> getTokenUsage() {
        return dashboardService.getTokenUsageTimeline();
    }

    // ── GET /evolution-history ────────────────────────────────────────────────

    /**
     * Returns the full EvolutionAgent refactoring changelog, newest-first.
     * Powers the "История изменений" tab on the MIDAS frontend dashboard.
     *
     * @return list of {@link EvolutionLogItemDto}; empty array before any cycles have run
     */
    @GetMapping("/evolution-history")
    public List<EvolutionLogItemDto> getEvolutionHistory() {
        return dashboardService.getEvolutionHistory();
    }
}
