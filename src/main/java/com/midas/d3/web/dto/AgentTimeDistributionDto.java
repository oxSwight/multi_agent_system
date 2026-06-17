package com.midas.d3.web.dto;

/**
 * Per-agent average execution time — drives the performance pie/bar chart
 * on the dashboard.
 *
 * @param agentName  simple class name of the agent (e.g. {@code "SystemAnalystAgent"})
 * @param avgTimeMs  mean wall-clock execution time in milliseconds,
 *                   computed only over successful (non-error) invocations
 */
public record AgentTimeDistributionDto(
        String agentName,
        long avgTimeMs
) {}
