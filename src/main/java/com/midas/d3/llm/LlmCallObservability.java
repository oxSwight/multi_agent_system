package com.midas.d3.llm;

import lombok.extern.slf4j.Slf4j;

/**
 * Centralised logging for LLM call telemetry (tokens, finish reason) and FinOps visibility.
 */
@Slf4j
public final class LlmCallObservability {

    private LlmCallObservability() {}

    /**
     * Logs per-call telemetry for triage during multi-attempt agent loops.
     */
    public static void logTelemetry(String runId,
                                    String agentName,
                                    int attempt,
                                    int maxAttempts,
                                    LlmCallResult result) {
        log.info("[LLM Telemetry] run={} Agent: {}, Tokens: {}/{}, Finish Reason: {} (attempt {}/{})",
                runId, agentName, result.promptTokens(), result.completionTokens(),
                nullToDash(result.finishReason()), attempt, maxAttempts);

        if (result.isTruncated()) {
            log.warn("[LLM Telemetry] run={} agent={} attempt={}/{} — response truncated (MAX_TOKENS), "
                            + "retry is useless without reducing scope",
                    runId, agentName, attempt, maxAttempts);
        }
    }

    /**
     * Logs accumulated telemetry at the end of an agent or coordinator pass.
     */
    public static void logExecutionSummary(String runId,
                                           String agentName,
                                           int promptTokens,
                                           int completionTokens,
                                           String finishReason) {
        log.info("[LLM Telemetry] run={} Agent: {}, Tokens: {}/{}, Finish Reason: {}",
                runId, agentName, promptTokens, completionTokens, nullToDash(finishReason));
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
