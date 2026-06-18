package com.midas.d3.llm;

import com.midas.d3.statemachine.MidasState;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class LlmModelPolicy {

    private final LlmModelPolicyProperties properties;

    public LlmModelPolicy(LlmModelPolicyProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    public String resolve(MidasState stage) {
        Objects.requireNonNull(stage, "stage must not be null");
        String mapped = properties.getStageModels().get(stage.name());
        if (mapped != null && !mapped.isBlank()) {
            return mapped.trim();
        }
        return properties.getModel();
    }
}
