package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Contract for validating an agent's JSON output against its structural schema.
 *
 * <p>Each pipeline stage has a dedicated implementation that checks:
 * <ul>
 *   <li>Syntactic JSON validity (Jackson parse guard)</li>
 *   <li>Presence and types of required fields</li>
 *   <li>Domain constraints (non-empty arrays, valid enums, etc.)</li>
 * </ul>
 *
 * <p>Implementations must be stateless and thread-safe.
 */
public interface GoalKeeperValidator {

    /**
     * Returns the agent name this validator is responsible for.
     * Used in error messages and logging.
     */
    String agentName();

    /**
     * Returns the pipeline stage identifier.
     */
    String stage();

    /**
     * Validates {@code rawJson} — the raw string output from an LLM call.
     *
     * @param rawJson string output from the LLM; may be null, blank, or malformed
     * @return parsed, validated {@link JsonNode} ready to store in {@link com.midas.d3.context.MidasContext}
     * @throws ValidationHookException if any structural constraint is violated
     */
    JsonNode validate(String rawJson) throws ValidationHookException;

    /**
     * Convenience overload for re-validating an already-parsed node
     * (e.g., after retry enrichment).
     *
     * @param node pre-parsed JsonNode; may be null
     * @throws ValidationHookException if any structural constraint is violated
     */
    default JsonNode validate(JsonNode node) throws ValidationHookException {
        if (node == null || node.isNull() || node.isMissingNode()) {
            throw new ValidationHookException(agentName(), stage(),
                    "Input JsonNode is null or missing.");
        }
        return validate(node.toString());
    }
}
