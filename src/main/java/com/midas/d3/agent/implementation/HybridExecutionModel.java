package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.midas.d3.context.MidasContext;

/**
 * Detects whether the pipeline should fork {@code CODE_GENERATION} into bounded
 * client/server implementation passes.
 */
public final class HybridExecutionModel {

    private HybridExecutionModel() {}

    /** {@code true} when the validated technical spec declares {@code execution_model: HYBRID}. */
    public static boolean isHybrid(MidasContext context) {
        if (context == null) {
            return false;
        }
        return isHybrid(context.getTechnicalSpec());
    }

    /** {@code true} when {@code runtime_environment.execution_model} is {@code HYBRID}. */
    public static boolean isHybrid(JsonNode technicalSpec) {
        if (technicalSpec == null || !technicalSpec.isObject()) {
            return false;
        }
        JsonNode env = technicalSpec.get("runtime_environment");
        if (env == null || !env.isObject()) {
            return false;
        }
        return "HYBRID".equalsIgnoreCase(env.path("execution_model").asText(""));
    }
}
