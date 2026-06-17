package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.midas.d3.context.MidasContext;

import java.util.Optional;

public final class HybridExecutionModel {

    private HybridExecutionModel() {}

    public static boolean isHybrid(MidasContext context) {
        if (context == null) {
            return false;
        }
        return isHybrid(context.getTechnicalSpec());
    }

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

    public static Optional<ImplementationSurface> singlePassSurface(MidasContext context) {
        if (context == null) {
            return Optional.empty();
        }
        return singlePassSurface(context.getTechnicalSpec());
    }

    public static Optional<ImplementationSurface> singlePassSurface(JsonNode technicalSpec) {
        if (technicalSpec == null || !technicalSpec.isObject()) {
            return Optional.empty();
        }
        JsonNode env = technicalSpec.get("runtime_environment");
        if (env == null || !env.isObject()) {
            return Optional.empty();
        }
        String model = env.path("execution_model").asText("");
        if ("CLIENT_SIDE".equalsIgnoreCase(model)) {
            return Optional.of(ImplementationSurface.CLIENT);
        }
        if ("SERVER_SIDE".equalsIgnoreCase(model)) {
            return Optional.of(ImplementationSurface.SERVER);
        }
        return Optional.empty();
    }
}
