package com.midas.d3.agent.base;

import com.midas.d3.context.ContextReducer;

/**
 * Thrown by {@link BaseMidasAgent} when the LLM agent exhausts all internal
 * retry attempts without producing a valid, GoalKeeper-approved JSON artifact.
 *
 * <p>Callers (e.g. pipeline orchestration layer) should treat this as a
 * terminal failure for the current pipeline stage.
 *
 * <p>Non-final so {@link com.midas.d3.agent.implementation.CodeGapDegradationException} can extend it:
 * that subtype carries a salvageable partial artifact for graceful degradation, yet still behaves as an
 * {@code AgentExecutionException} for every existing {@code catch} (the dispatcher catches the subtype first).
 */
public class AgentExecutionException extends RuntimeException {

    private final String                  agentName;
    private final ContextReducer.AgentRole role;
    private final int                     maxAttempts;

    public AgentExecutionException(String agentName,
                                   ContextReducer.AgentRole role,
                                   int maxAttempts,
                                   String lastError) {
        super("Agent [%s] (role=%s) exhausted %d attempts. Last error: %s"
                .formatted(agentName, role, maxAttempts, lastError));
        this.agentName   = agentName;
        this.role        = role;
        this.maxAttempts = maxAttempts;
    }

    public String getAgentName()              { return agentName;   }
    public ContextReducer.AgentRole getRole() { return role;        }
    public int getMaxAttempts()               { return maxAttempts; }
}
