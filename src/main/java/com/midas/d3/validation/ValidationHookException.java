package com.midas.d3.validation;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Thrown when a GoalKeeperValidator detects that an agent's JSON output
 * violates its structural contract. Carries a full list of violation
 * messages for diagnostic logging and retry decisions.
 */
public final class ValidationHookException extends RuntimeException {

    private final String agentName;
    private final String stage;
    private final List<String> violations;
    private final boolean parseError;

    public ValidationHookException(String agentName, String stage, List<String> violations) {
        this(agentName, stage, violations, detectLegacyParseError(violations));
    }

    public ValidationHookException(String agentName, String stage, String singleViolation) {
        this(agentName, stage, singleViolation == null
                ? Collections.emptyList()
                : List.of(singleViolation));
    }

    private ValidationHookException(String agentName, String stage,
                                    List<String> violations, boolean parseError) {
        super(buildMessage(agentName, stage, violations));
        this.agentName  = Objects.requireNonNull(agentName, "agentName must not be null");
        this.stage      = Objects.requireNonNull(stage, "stage must not be null");
        this.violations = violations == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(violations);
        this.parseError = parseError;
    }

    /**
     * Factory for an unparseable / structurally-unreadable LLM response (e.g. no JSON, or no
     * markdown code block where one is required). Parse failures get the capped fail-fast retry
     * budget ({@link com.midas.d3.agent.AgentRetryPolicy#maxParseAttempts()}) rather than the full
     * schema-correction budget — retrying garbage output rarely helps and only burns calls.
     */
    public static ValidationHookException parseFailure(String agentName, String stage, String message) {
        return new ValidationHookException(agentName, stage,
                message == null ? Collections.emptyList() : List.of(message), true);
    }

    public String getAgentName()       { return agentName; }
    public String getStage()           { return stage; }
    public List<String> getViolations(){ return violations; }

    /** {@code true} when the LLM output could not be parsed into the expected structure. */
    public boolean isParseError() {
        return parseError;
    }

    private static boolean detectLegacyParseError(List<String> violations) {
        return violations != null && violations.size() == 1
                && violations.get(0).startsWith("JSON parse error:");
    }

    private static String buildMessage(String agentName, String stage, List<String> violations) {
        StringBuilder sb = new StringBuilder();
        sb.append("Validation failed for agent [").append(agentName)
          .append("] at stage [").append(stage).append("]");

        if (violations != null && !violations.isEmpty()) {
            sb.append(": ");
            for (int i = 0; i < violations.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(violations.get(i));
            }
        }
        return sb.toString();
    }
}
