package com.midas.d3.context;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.Map;

/**
 * Minimal, read-only context projection passed to a specific agent.
 * Contains only the fields that agent actually needs — prevents token bloat
 * and leaks of irrelevant artifacts.
 */
@Getter
@Builder
public final class AgentContextView {

    /** Identifies which agent produced / will consume this view. */
    private final String agentName;

    /** Pipeline run identifier for traceability. */
    private final String pipelineRunId;

    /** The original user idea, always included for grounding. */
    private final String rawUserIdea;

    /**
     * Upstream artifacts needed by this specific agent.
     * Key = logical name (e.g. "technicalSpec"), value = JsonNode.
     * Immutable at construction time.
     */
    private final Map<String, JsonNode> requiredArtifacts;

    /** Compact serialization hint — approximate token budget. */
    private final int estimatedTokenBudget;

    public Map<String, JsonNode> safeArtifacts() {
        return requiredArtifacts == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(requiredArtifacts);
    }
}
