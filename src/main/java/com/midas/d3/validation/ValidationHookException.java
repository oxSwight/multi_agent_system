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

    public ValidationHookException(String agentName, String stage, List<String> violations) {
        super(buildMessage(agentName, stage, violations));
        this.agentName  = Objects.requireNonNull(agentName, "agentName must not be null");
        this.stage      = Objects.requireNonNull(stage, "stage must not be null");
        this.violations = violations == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(violations);
    }

    public ValidationHookException(String agentName, String stage, String singleViolation) {
        this(agentName, stage, singleViolation == null
                ? Collections.emptyList()
                : List.of(singleViolation));
    }

    public String getAgentName()       { return agentName; }
    public String getStage()           { return stage; }
    public List<String> getViolations(){ return violations; }

    /** {@code true} when Jackson failed to parse the LLM output as JSON. */
    public boolean isParseError() {
        return violations.size() == 1
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
