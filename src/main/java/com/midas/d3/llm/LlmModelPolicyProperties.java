package com.midas.d3.llm;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "midas.llm")
public class LlmModelPolicyProperties {

    /** Primary (heavy-reasoning) model — the default tier for every stage. */
    private String model = "gemini-2.5-flash";

    /**
     * Fast / cheap fallback model for trivial, structured, or non-critical stages.
     * Blank disables tier-down entirely (every stage resolves to {@link #model}), so the feature
     * is opt-in and the default deployment is unaffected until an operator configures this.
     */
    private String fastModel = "";

    /**
     * Stages eligible for the fast tier (by {@code MidasState} name). When empty the policy applies
     * its conservative built-in default. Critical gates (SecOps audit, the Product-Review quality
     * gate) and heavy generation stages are deliberately excluded — cheapening a blocking gate is
     * exactly the false-economy the council assessment warned against.
     */
    private Set<String> fastStages = new LinkedHashSet<>();

    /** Explicit per-stage model pins. Highest precedence — an operator override always wins. */
    private Map<String, String> stageModels = new LinkedHashMap<>();
}
