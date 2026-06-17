package com.midas.d3.web.dto;

/**
 * Per-run token consumption data point for the token-usage line chart.
 *
 * <p>Runs are returned newest-first so the frontend can render a chronological
 * timeline by reversing the array or relying on index order.
 *
 * @param runId             unique pipeline run identifier
 * @param promptTokens      total prompt tokens consumed during this run
 * @param completionTokens  total completion tokens consumed during this run
 */
public record TokenUsageTimelineDto(
        String runId,
        int promptTokens,
        int completionTokens
) {}
