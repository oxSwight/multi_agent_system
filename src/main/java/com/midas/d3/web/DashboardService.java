package com.midas.d3.web;

import com.midas.d3.persistence.entity.MidasRunEntity;
import com.midas.d3.persistence.repository.MidasAgentLogRepository;
import com.midas.d3.persistence.repository.MidasEvolutionLogRepository;
import com.midas.d3.persistence.repository.MidasRunRepository;
import com.midas.d3.web.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Aggregation service for the MIDAS web dashboard.
 *
 * <p>All methods are read-only transactions — no write operations are performed.
 * Data is aggregated directly at the DB level via JPQL queries where possible
 * to avoid loading large result sets into the JVM.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DashboardService {

    private final MidasRunRepository          runRepository;
    private final MidasAgentLogRepository     agentLogRepository;
    private final MidasEvolutionLogRepository evolutionLogRepository;
    private final FinOpsCostEstimator         finOpsCostEstimator;

    // ── Overview ─────────────────────────────────────────────────────────────

    /**
     * Computes the high-level KPIs displayed on the dashboard overview card:
     * run count, token totals, mean agent execution time, and the most
     * token-hungry agent type.
     *
     * <p>All aggregate queries run as individual, indexed SQL statements so the
     * method is O(1) in data volume (no full table scans in the JVM).
     *
     * @return assembled {@link OverviewMetricsDto}; never {@code null}
     */
    public OverviewMetricsDto getOverviewMetrics() {
        long totalRuns = runRepository.count();

        Object[] tokenSums = runRepository.sumTotalTokens();
        long totalPromptTokens     = tokenSums[0] != null ? ((Number) tokenSums[0]).longValue() : 0L;
        long totalCompletionTokens = tokenSums[1] != null ? ((Number) tokenSums[1]).longValue() : 0L;

        Double avgMs = agentLogRepository.avgExecutionTimeMs();
        long avgExecutionTimeMs = avgMs != null ? Math.round(avgMs) : 0L;

        List<Object[]> tokenRanking = agentLogRepository.findAgentsByTotalTokenConsumption();
        String mostExpensiveAgent = tokenRanking.isEmpty() ? null : (String) tokenRanking.get(0)[0];

        log.debug("[Dashboard] overview: runs={}, promptTokens={}, completionTokens={}, avgMs={}, topAgent={}",
                totalRuns, totalPromptTokens, totalCompletionTokens, avgExecutionTimeMs, mostExpensiveAgent);

        return new OverviewMetricsDto(
                totalRuns, totalPromptTokens, totalCompletionTokens, avgExecutionTimeMs, mostExpensiveAgent);
    }

    // ── Runs list ────────────────────────────────────────────────────────────

    /**
     * Returns a summary projection of every pipeline run, ordered newest-first.
     *
     * @return list of {@link RunItemDto}; empty list when no runs exist
     */
    public List<RunItemDto> getAllRuns() {
        return runRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(RunItemDto::from)
                .toList();
    }

    // ── Run detail ───────────────────────────────────────────────────────────

    /**
     * Returns the full detail view of a single pipeline run, including all
     * ordered agent invocation logs.
     *
     * @param runId the pipeline run identifier
     * @return assembled {@link RunDetailsDto}
     * @throws RunNotFoundException if no run with the given ID exists
     */
    public RunDetailsDto getRunDetails(String runId) {
        MidasRunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> new RunNotFoundException("Pipeline run not found: " + runId));

        List<AgentLogDto> logs = agentLogRepository.findByRunId(runId).stream()
                .map(entity -> AgentLogDto.from(entity, finOpsCostEstimator))
                .toList();

        return RunDetailsDto.from(run, logs, finOpsCostEstimator);
    }

    // ── Performance chart ────────────────────────────────────────────────────

    /**
     * Returns per-agent average execution times for the performance pie/bar chart.
     * Only successful (non-error) invocations are included in the averages.
     *
     * @return list ordered by avg execution time descending; empty when no logs exist
     */
    public List<AgentTimeDistributionDto> getPerformanceStats() {
        return agentLogRepository.findPerformanceStatsByAgentType().stream()
                .map(row -> new AgentTimeDistributionDto(
                        (String) row[0],
                        Math.round(((Number) row[1]).doubleValue())
                ))
                .toList();
    }

    // ── Token-usage timeline ─────────────────────────────────────────────────

    /**
     * Returns per-run token consumption data points for the line chart, newest-first.
     * Consumers may reverse the list to render a chronological timeline.
     *
     * @return list of {@link TokenUsageTimelineDto}; empty when no runs exist
     */
    public List<TokenUsageTimelineDto> getTokenUsageTimeline() {
        return runRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(r -> new TokenUsageTimelineDto(
                        r.getId(),
                        r.getTotalPromptTokens(),
                        r.getTotalCompletionTokens()
                ))
                .toList();
    }

    // ── Evolution history ─────────────────────────────────────────────────────

    /**
     * Returns all EvolutionAgent refactoring reports ordered newest-first.
     * Powers the "История изменений" (Changelog) tab on the frontend dashboard.
     *
     * @return list of {@link EvolutionLogItemDto}; empty when no cycles have run
     */
    public List<EvolutionLogItemDto> getEvolutionHistory() {
        return evolutionLogRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(EvolutionLogItemDto::from)
                .toList();
    }
}
