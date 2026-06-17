package com.midas.d3.web.dto;

/**
 * Aggregate overview metrics for the MIDAS dashboard overview card.
 *
 * @param totalRuns              total number of pipeline runs recorded in the DB
 * @param totalPromptTokens      sum of all prompt tokens across every run
 * @param totalCompletionTokens  sum of all completion tokens across every run
 * @param avgExecutionTimeMs     mean agent execution time (successful invocations only)
 * @param mostExpensiveAgent     agent type with the highest cumulative token consumption,
 *                               or {@code null} when no agent logs exist yet
 */
public record OverviewMetricsDto(
        long totalRuns,
        long totalPromptTokens,
        long totalCompletionTokens,
        long avgExecutionTimeMs,
        String mostExpensiveAgent
) {}
