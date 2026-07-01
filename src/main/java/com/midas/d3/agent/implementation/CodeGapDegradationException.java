package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.midas.d3.agent.base.AgentExecutionException;
import com.midas.d3.context.ContextReducer;

import java.util.List;

/**
 * Thrown when CODE_GENERATION hits an <em>unhealable functional gap</em> — the assembled-envelope gate
 * exhausted its self-healing budget — but a best-effort partial artifact is in hand.
 *
 * <p>Unlike a bare {@link AgentExecutionException} (which the dispatcher routes to a client-visible
 * {@code CRITICAL FAILURE}), this subtype <b>carries the salvageable partial</b> (source files, feature
 * manifest) and the <b>specific unmet gaps</b>, so {@link com.midas.d3.statemachine.AgentDispatcher} can
 * deliver a {@code COMPLETED_WITH_GAPS} product with an honest coverage report instead of crashing.
 *
 * <p>It extends {@link AgentExecutionException} so it still flows unchanged through the HYBRID fan-out's
 * {@code CompletableFuture} unwrapping and any existing {@code catch (AgentExecutionException)} — the
 * dispatcher simply catches this subtype <em>first</em>. When degradation is disabled or the partial is
 * not salvageable, the dispatcher falls back to the same CRITICAL FAILURE path, so behavior is unchanged.
 */
public final class CodeGapDegradationException extends AgentExecutionException {

    private final transient JsonNode partialSource;
    private final transient JsonNode featureManifest;
    private final transient List<String> gaps;

    public CodeGapDegradationException(String agentName,
                                       ContextReducer.AgentRole role,
                                       int attemptsUsed,
                                       String lastError,
                                       JsonNode partialSource,
                                       JsonNode featureManifest,
                                       List<String> gaps) {
        super(agentName, role, attemptsUsed, lastError);
        this.partialSource   = partialSource;
        this.featureManifest = featureManifest;
        this.gaps            = gaps == null ? List.of() : List.copyOf(gaps);
    }

    public JsonNode getPartialSource()   { return partialSource; }
    public JsonNode getFeatureManifest() { return featureManifest; }
    public List<String> getGaps()        { return gaps; }

    /** True when there is at least one generated file to deliver — the precondition for degrading. */
    public boolean hasSalvageablePartial() {
        return partialSource != null && partialSource.isObject() && !partialSource.isEmpty();
    }
}
