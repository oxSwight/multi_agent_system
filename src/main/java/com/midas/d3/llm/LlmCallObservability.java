package com.midas.d3.llm;

import lombok.extern.slf4j.Slf4j;

/**
 * Centralised logging for LLM call metadata (finish reason, response size) and FinOps token usage.
 */
@Slf4j
public final class LlmCallObservability {

    private LlmCallObservability() {}

    /**
     * Logs per-call metadata required for triage: finishReason, response size, model, attempt, runId.
     * Emits an explicit WARN when the response was truncated by the model token limit.
     */
    public static void logCallMetadata(String runId,
                                       String agentName,
                                       int attempt,
                                       int maxAttempts,
                                       LlmCallResult result) {
        int responseChars = result.text() != null ? result.text().length() : 0;
        log.info("[LLM] run={} agent={} attempt={}/{} model={} finishReason={} responseChars={}",
                runId, agentName, attempt, maxAttempts, result.modelUsed(),
                nullToDash(result.finishReason()), responseChars);

        if (result.isTruncated()) {
            log.warn("[LLM] run={} agent={} attempt={}/{} — ответ обрезан (MAX_TOKENS), "
                            + "retry бесполезен без уменьшения scope",
                    runId, agentName, attempt, maxAttempts);
        }
    }

    /** Logs token consumption after each LLM call for FinOps visibility. */
    public static void logFinOps(String runId,
                                 String agentName,
                                 String model,
                                 int promptTokens,
                                 int completionTokens) {
        if (promptTokens == 0 && completionTokens == 0) {
            return;
        }
        log.info("[FinOps] run={} agent={} model={} prompt={} completion={} total={}",
                runId, agentName, nullToDash(model), promptTokens, completionTokens,
                promptTokens + completionTokens);
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
