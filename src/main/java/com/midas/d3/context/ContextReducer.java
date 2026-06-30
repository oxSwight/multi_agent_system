package com.midas.d3.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.midas.d3.agent.implementation.ArchitectureSurfaceSlicer;
import com.midas.d3.agent.implementation.ControllerEvidenceBuilder;
import com.midas.d3.agent.implementation.ImplementationSurface;
import com.midas.d3.agent.implementation.SourceMapPathFilter;
import com.midas.d3.agent.implementation.SourceMapSlicer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reduces a full {@link MidasContext} to the minimum artifact set required
 * by a specific pipeline agent. Prevents token bloat and protects agents
 * from artifacts they must not be influenced by (e.g., source code should
 * not leak into the System Analyst prompt).
 *
 * <p>Each {@link AgentRole} has a declared dependency list. Only those
 * JsonNode artifacts are included in the returned {@link AgentContextView}.
 * The resulting view is serialized to a compact JSON string via
 * {@link #toCompactJson(AgentContextView)}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextReducer {

    private final ObjectMapper objectMapper;

    @Value("${midas.context.max-artifact-size-kb:512}")
    private int maxArtifactSizeKb;

    // ── Agent Role → Required Artifact Keys ─────────────────────────────────

    public enum AgentRole {
        SYSTEM_ANALYST,
        SOFTWARE_ARCHITECT,
        INTEGRATION_ENGINEER,
        IMPLEMENTATION_ENGINEER,
        QA_ENGINEER,
        BUILD_VERIFIER,
        SECOPS_ENGINEER,
        CONTROLLER
    }

    /**
     * A single upstream-artifact dependency of an agent role.
     *
     * <p><b>Skip-aware semantics.</b> An {@code optional} dependency is produced by a stage that
     * dynamic routing may legitimately skip (e.g. {@code integrationStrategy} when a product has
     * no external integrations). When such an artifact is absent the reducer silently omits it and
     * still delivers the rest of the context. A {@code required} dependency is produced by a stage
     * that always runs on the path to this agent — its absence is a genuine pipeline defect and is
     * surfaced immediately via {@link IllegalArgumentException} (fail-fast).
     */
    public record ArtifactDependency(String key, boolean optional) {
        public ArtifactDependency {
            Objects.requireNonNull(key, "artifact key must not be null");
        }
        static ArtifactDependency required(String key) { return new ArtifactDependency(key, false); }
        static ArtifactDependency optional(String key) { return new ArtifactDependency(key, true); }
    }

    private static final EnumMap<AgentRole, List<ArtifactDependency>> ARTIFACT_DEPENDENCIES =
            new EnumMap<>(AgentRole.class);

    static {
        // De-siloing: the runtime_environment boundary lives in technicalSpec and is echoed by
        // architectureDesign. Every downstream agent must see those artifacts so the boundary
        // (client-side vs server-side, forbidden infrastructure, etc.) propagates end-to-end and
        // is never lost between stages — these are therefore REQUIRED everywhere they appear.
        //
        // Artifacts whose producing stage is skip-eligible under dynamic routing are marked
        // OPTIONAL so a skipped stage never starves a downstream agent of its remaining context.
        ARTIFACT_DEPENDENCIES.put(AgentRole.SYSTEM_ANALYST, List.of());
        ARTIFACT_DEPENDENCIES.put(AgentRole.SOFTWARE_ARCHITECT, List.of(
                ArtifactDependency.required("technicalSpec")));
        ARTIFACT_DEPENDENCIES.put(AgentRole.INTEGRATION_ENGINEER, List.of(
                ArtifactDependency.required("technicalSpec"),
                ArtifactDependency.required("architectureDesign")));
        ARTIFACT_DEPENDENCIES.put(AgentRole.IMPLEMENTATION_ENGINEER, List.of(
                ArtifactDependency.required("technicalSpec"),
                ArtifactDependency.required("architectureDesign"),
                ArtifactDependency.optional("integrationStrategy"),
                ArtifactDependency.optional("remediationDirective")));
        ARTIFACT_DEPENDENCIES.put(AgentRole.QA_ENGINEER, List.of(
                ArtifactDependency.required("technicalSpec"),
                ArtifactDependency.required("architectureDesign"),
                ArtifactDependency.required("generatedSourceCode"),
                ArtifactDependency.optional("remediationDirective")));
        // The build verifier compiles what was actually generated. It needs the runtime boundary
        // (technicalSpec + architectureDesign) for context and both the source and test maps, which
        // it materializes and builds. generatedTests is optional — a project may legitimately ship
        // without a separate test map, and the source still compiles on its own.
        ARTIFACT_DEPENDENCIES.put(AgentRole.BUILD_VERIFIER, List.of(
                ArtifactDependency.required("technicalSpec"),
                ArtifactDependency.required("architectureDesign"),
                ArtifactDependency.required("generatedSourceCode"),
                ArtifactDependency.optional("generatedTests")));
        ARTIFACT_DEPENDENCIES.put(AgentRole.SECOPS_ENGINEER, List.of(
                ArtifactDependency.required("technicalSpec"),
                ArtifactDependency.required("architectureDesign"),
                ArtifactDependency.required("generatedSourceCode"),
                ArtifactDependency.required("generatedTests"),
                ArtifactDependency.optional("remediationDirective")));
        // The Controller is the blocking Product-Owner gate. To keep it well under token limits
        // it judges intent conformance from the original spec (business_goal + requested features)
        // and the SecOps artifacts (which carry the release_artifacts map of what was actually
        // shipped) — deliberately NOT the full raw source/test bodies. Both are produced by stages
        // that always run on the path to this gate, so both are required (fail-fast if absent).
        ARTIFACT_DEPENDENCIES.put(AgentRole.CONTROLLER, List.of(
                ArtifactDependency.required("technicalSpec"),
                ArtifactDependency.required("secOpsArtifacts"),
                ArtifactDependency.required("featureManifest")));
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Builds a minimal {@link AgentContextView} for the given role.
     *
     * @param context  full pipeline context; must not be null
     * @param role     target agent role; must not be null
     * @return trimmed, immutable view
     * @throws IllegalArgumentException if required upstream artifacts are missing
     */
    public AgentContextView reduce(MidasContext context, AgentRole role) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(role, "role must not be null");

        List<ArtifactDependency> dependencies = ARTIFACT_DEPENDENCIES.get(role);
        Map<String, JsonNode> artifacts = new LinkedHashMap<>();

        for (ArtifactDependency dep : dependencies) {
            JsonNode node = resolveArtifact(context, dep.key());
            boolean absent = node == null || node.isNull() || node.isMissingNode();

            if (absent) {
                if (dep.optional()) {
                    // Skip-aware: the producing stage was routed around — omit gracefully so the
                    // agent still receives the remainder of its required context.
                    log.debug("ContextReducer → role={} optional artifact [{}] absent (stage skipped) — omitting.",
                            role, dep.key());
                    continue;
                }
                throw new IllegalArgumentException(
                        "Agent [%s] requires artifact [%s] but it is absent in the current context."
                                .formatted(role, dep.key()));
            }
            artifacts.put(dep.key(), node);
        }

        // F3 — Controller with proof: augment the (file-name-only) feature_manifest with a deterministic,
        // capability-level implementationEvidence digest (functional-coverage status per acceptance
        // criterion) so the blocking Product-Owner gate can confirm coverage from machine-checked
        // evidence rather than inferring it from file names. Kept at capability altitude on purpose: a
        // finer per-file digest pushed the LLM gate into nitpicking body/field details it could not see
        // and false-rejecting sound products. Raw source bodies are never sent. Attached only when it
        // carries a real signal, so a shape with no checkable criteria behaves exactly as before.
        if (role == AgentRole.CONTROLLER) {
            JsonNode source = context.getGeneratedSourceCode();
            if (source != null && source.isObject() && !source.isEmpty()) {
                JsonNode evidence = ControllerEvidenceBuilder.build(
                        source, context.getTechnicalSpec(), objectMapper);
                if (evidence.path("functional_coverage").size() > 0) {
                    artifacts.put("implementationEvidence", evidence);
                }
            }
        }

        int estimatedTokens = estimateTokens(context.getRawUserIdea(), artifacts);

        log.debug("ContextReducer → role={} artifacts={} estimatedTokens={}",
                role, artifacts.keySet(), estimatedTokens);

        return AgentContextView.builder()
                .agentName(role.name())
                .pipelineRunId(context.getPipelineRunId())
                .rawUserIdea(context.getRawUserIdea())
                .requiredArtifacts(artifacts)
                .estimatedTokenBudget(estimatedTokens)
                .build();
    }

    /**
     * Builds a context view for one bounded HYBRID implementation pass. Starts from the standard
     * {@link AgentRole#IMPLEMENTATION_ENGINEER} dependency set, then slices {@code architectureDesign}
     * to the client or server surface so each LLM call avoids irrelevant boilerplate.
     */
    public AgentContextView reduceImplementationPass(MidasContext context, ImplementationSurface surface) {
        Objects.requireNonNull(surface, "surface must not be null");
        AgentContextView base = reduce(context, AgentRole.IMPLEMENTATION_ENGINEER);
        Map<String, JsonNode> artifacts = new LinkedHashMap<>(base.safeArtifacts());

        JsonNode architecture = artifacts.get("architectureDesign");
        if (architecture != null && !architecture.isNull()) {
            artifacts.put("architectureDesign",
                    ArchitectureSurfaceSlicer.slice(architecture, surface, objectMapper));
        }

        int estimatedTokens = estimateTokens(context.getRawUserIdea(), artifacts);

        log.debug("ContextReducer → HYBRID {} pass artifacts={} estimatedTokens={}",
                surface, artifacts.keySet(), estimatedTokens);

        return AgentContextView.builder()
                .agentName("IMPLEMENTATION_ENGINEER_" + surface.name())
                .pipelineRunId(context.getPipelineRunId())
                .rawUserIdea(context.getRawUserIdea())
                .requiredArtifacts(artifacts)
                .estimatedTokenBudget(estimatedTokens)
                .build();
    }

    public AgentContextView reduceTestGenerationPass(MidasContext context, ImplementationSurface surface) {
        Objects.requireNonNull(surface, "surface must not be null");
        AgentContextView base = reduce(context, AgentRole.QA_ENGINEER);
        Map<String, JsonNode> artifacts = new LinkedHashMap<>(base.safeArtifacts());

        JsonNode architecture = artifacts.get("architectureDesign");
        if (architecture != null && !architecture.isNull()) {
            artifacts.put("architectureDesign",
                    ArchitectureSurfaceSlicer.slice(architecture, surface, objectMapper));
        }

        JsonNode sourceCode = artifacts.get("generatedSourceCode");
        if (sourceCode != null && !sourceCode.isNull()) {
            artifacts.put("generatedSourceCode",
                    SourceMapSlicer.slice(sourceCode, surface, objectMapper));
        }

        int estimatedTokens = estimateTokens(context.getRawUserIdea(), artifacts);

        log.debug("ContextReducer → HYBRID {} QA pass artifacts={} estimatedTokens={}",
                surface, artifacts.keySet(), estimatedTokens);

        return AgentContextView.builder()
                .agentName("QA_ENGINEER_" + surface.name())
                .pipelineRunId(context.getPipelineRunId())
                .rawUserIdea(context.getRawUserIdea())
                .requiredArtifacts(artifacts)
                .estimatedTokenBudget(estimatedTokens)
                .build();
    }

    public AgentContextView reducePatchImplementationPass(MidasContext context, List<String> affectedPaths) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(affectedPaths, "affectedPaths must not be null");

        Map<String, JsonNode> artifacts = new LinkedHashMap<>();
        requirePatchArtifact("technicalSpec", context.getTechnicalSpec(), artifacts);
        requirePatchArtifact("architectureDesign", context.getArchitectureDesign(), artifacts);
        includePatchOptional("integrationStrategy", context.getIntegrationStrategy(), artifacts);
        includePatchOptional("remediationDirective", context.getRemediationDirective(), artifacts);

        JsonNode source = context.getGeneratedSourceCode();
        if (source != null && !source.isNull() && source.isObject()) {
            artifacts.put("generatedSourceCode",
                    SourceMapPathFilter.filter(source, affectedPaths, objectMapper));
        }

        return buildPatchView("IMPLEMENTATION_ENGINEER_PATCH", context, artifacts);
    }

    public AgentContextView reducePatchImplementationPass(MidasContext context, List<String> affectedPaths,
                                                          ImplementationSurface surface) {
        Objects.requireNonNull(surface, "surface must not be null");
        AgentContextView base = reducePatchImplementationPass(context, affectedPaths);
        Map<String, JsonNode> artifacts = new LinkedHashMap<>(base.safeArtifacts());
        JsonNode architecture = artifacts.get("architectureDesign");
        if (architecture != null && !architecture.isNull()) {
            artifacts.put("architectureDesign",
                    ArchitectureSurfaceSlicer.slice(architecture, surface, objectMapper));
        }
        return AgentContextView.builder()
                .agentName("IMPLEMENTATION_ENGINEER_PATCH_" + surface.name())
                .pipelineRunId(context.getPipelineRunId())
                .rawUserIdea(context.getRawUserIdea())
                .requiredArtifacts(artifacts)
                .estimatedTokenBudget(estimateTokens(context.getRawUserIdea(), artifacts))
                .build();
    }

    public AgentContextView reducePatchTestPass(MidasContext context, List<String> affectedPaths,
                                                JsonNode patchedSource) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(affectedPaths, "affectedPaths must not be null");
        Objects.requireNonNull(patchedSource, "patchedSource must not be null");

        Map<String, JsonNode> artifacts = new LinkedHashMap<>();
        requirePatchArtifact("technicalSpec", context.getTechnicalSpec(), artifacts);
        requirePatchArtifact("architectureDesign", context.getArchitectureDesign(), artifacts);
        artifacts.put("generatedSourceCode",
                SourceMapPathFilter.filter(patchedSource, affectedPaths, objectMapper));
        includePatchOptional("remediationDirective", context.getRemediationDirective(), artifacts);

        return buildPatchView("QA_ENGINEER_PATCH", context, artifacts);
    }

    public AgentContextView reducePatchTestPass(MidasContext context, List<String> affectedPaths,
                                                JsonNode patchedSource, ImplementationSurface surface) {
        Objects.requireNonNull(surface, "surface must not be null");
        AgentContextView base = reducePatchTestPass(context, affectedPaths, patchedSource);
        Map<String, JsonNode> artifacts = new LinkedHashMap<>(base.safeArtifacts());
        JsonNode architecture = artifacts.get("architectureDesign");
        if (architecture != null && !architecture.isNull()) {
            artifacts.put("architectureDesign",
                    ArchitectureSurfaceSlicer.slice(architecture, surface, objectMapper));
        }
        JsonNode sourceCode = artifacts.get("generatedSourceCode");
        if (sourceCode != null && !sourceCode.isNull()) {
            artifacts.put("generatedSourceCode",
                    SourceMapSlicer.slice(sourceCode, surface, objectMapper));
        }
        return AgentContextView.builder()
                .agentName("QA_ENGINEER_PATCH_" + surface.name())
                .pipelineRunId(context.getPipelineRunId())
                .rawUserIdea(context.getRawUserIdea())
                .requiredArtifacts(artifacts)
                .estimatedTokenBudget(estimateTokens(context.getRawUserIdea(), artifacts))
                .build();
    }

    public AgentContextView reducePatchSecOpsPass(MidasContext context, List<String> affectedPaths,
                                                  JsonNode patchedSource, JsonNode patchedTests) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(affectedPaths, "affectedPaths must not be null");
        Objects.requireNonNull(patchedSource, "patchedSource must not be null");
        Objects.requireNonNull(patchedTests, "patchedTests must not be null");

        Map<String, JsonNode> artifacts = new LinkedHashMap<>();
        requirePatchArtifact("technicalSpec", context.getTechnicalSpec(), artifacts);
        requirePatchArtifact("architectureDesign", context.getArchitectureDesign(), artifacts);
        artifacts.put("generatedSourceCode",
                SourceMapPathFilter.filter(patchedSource, affectedPaths, objectMapper));
        artifacts.put("generatedTests",
                SourceMapPathFilter.filter(patchedTests, affectedPaths, objectMapper));
        includePatchOptional("remediationDirective", context.getRemediationDirective(), artifacts);

        return buildPatchView("SECOPS_ENGINEER_PATCH", context, artifacts);
    }

    /**
     * Serializes an {@link AgentContextView} to a compact (non-pretty) JSON string.
     * Validates size against configured limit.
     *
     * @throws ContextSizeExceededException if serialized payload exceeds max size
     */
    public String toCompactJson(AgentContextView view) {
        Objects.requireNonNull(view, "view must not be null");

        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("agentName",    view.getAgentName());
            root.put("pipelineRunId", view.getPipelineRunId());
            root.put("rawUserIdea",  view.getRawUserIdea());

            ObjectNode artifactsNode = objectMapper.createObjectNode();
            view.safeArtifacts().forEach(artifactsNode::set);
            root.set("artifacts", artifactsNode);

            String json = objectMapper.writeValueAsString(root);

            long sizeKb = json.length() / 1024L;
            if (sizeKb > maxArtifactSizeKb) {
                throw new ContextSizeExceededException(
                        "Serialized context for agent [%s] is %d KB, exceeds limit of %d KB."
                                .formatted(view.getAgentName(), sizeKb, maxArtifactSizeKb));
            }

            return json;

        } catch (JsonProcessingException e) {
            throw new ContextSerializationException(
                    "Failed to serialize AgentContextView for [%s]".formatted(view.getAgentName()), e);
        }
    }

    /**
     * Fail-closed prompt-budget guard for the LLM-call path.
     *
     * <p>The agents assemble their own prompt text (rather than going through
     * {@link #toCompactJson(AgentContextView)}), so the serialized-size ceiling enforced there was
     * never applied to the payload actually sent to the model. This guard re-applies that ceiling to
     * the assembled {@code systemPrompt + userMessage}: when the payload exceeds the configured
     * {@code midas.context.max-artifact-size-kb} limit it throws {@link ContextSizeExceededException}
     * <em>before</em> the network call, so an over-budget context fails loudly here instead of
     * silently overflowing the model's context window (where the backend truncates the tail and the
     * agent is left to hallucinate around the missing content).
     *
     * <p>This is the detection half of dynamic payload management; deterministic
     * chunking/summarization of an over-budget context is a separate concern layered on top.
     *
     * @throws ContextSizeExceededException if the assembled prompt exceeds the configured ceiling
     */
    public void enforcePromptBudget(String agentName, String systemPrompt, String userMessage) {
        Objects.requireNonNull(agentName, "agentName must not be null");
        long sizeKb = (safeLength(systemPrompt) + safeLength(userMessage)) / 1024L;
        if (sizeKb > maxArtifactSizeKb) {
            throw new ContextSizeExceededException(
                    ("Assembled prompt for agent [%s] is %d KB, exceeds the configured limit of %d KB"
                            + " — refusing to call the LLM to avoid silent context-window overflow.")
                            .formatted(agentName, sizeKb, maxArtifactSizeKb));
        }
    }

    // ── Private Helpers ──────────────────────────────────────────────────────

    private static long safeLength(String value) {
        return value == null ? 0L : value.length();
    }

    private JsonNode resolveArtifact(MidasContext ctx, String key) {
        return switch (key) {
            case "technicalSpec"      -> ctx.getTechnicalSpec();
            case "architectureDesign" -> ctx.getArchitectureDesign();
            case "integrationStrategy"-> ctx.getIntegrationStrategy();
            case "generatedSourceCode"-> ctx.getGeneratedSourceCode();
            case "generatedTests"     -> ctx.getGeneratedTests();
            case "secOpsArtifacts"    -> ctx.getSecOpsArtifacts();
            case "featureManifest"    -> ctx.getFeatureManifest();
            case "remediationDirective" -> ctx.getRemediationDirective();
            default -> throw new IllegalArgumentException("Unknown artifact key: " + key);
        };
    }

    /**
     * Rough token estimate: 1 token ≈ 4 chars (industry-standard approximation).
     */
    private int estimateTokens(String rawIdea, Map<String, JsonNode> artifacts) {
        long charCount = (rawIdea == null ? 0 : rawIdea.length());
        for (JsonNode node : artifacts.values()) {
            charCount += node.toString().length();
        }
        return (int) Math.min(charCount / 4L, Integer.MAX_VALUE);
    }

    private void requirePatchArtifact(String key, JsonNode node, Map<String, JsonNode> out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            throw new IllegalArgumentException(
                    "Patch reducer requires artifact [%s] but it is absent in the current context.".formatted(key));
        }
        out.put(key, node);
    }

    private void includePatchOptional(String key, JsonNode node, Map<String, JsonNode> out) {
        if (node != null && !node.isNull() && !node.isMissingNode()) {
            out.put(key, node);
        }
    }

    private AgentContextView buildPatchView(String agentName, MidasContext context,
                                            Map<String, JsonNode> artifacts) {
        int estimatedTokens = estimateTokens(context.getRawUserIdea(), artifacts);
        log.debug("ContextReducer → {} artifacts={} estimatedTokens={}",
                agentName, artifacts.keySet(), estimatedTokens);
        return AgentContextView.builder()
                .agentName(agentName)
                .pipelineRunId(context.getPipelineRunId())
                .rawUserIdea(context.getRawUserIdea())
                .requiredArtifacts(artifacts)
                .estimatedTokenBudget(estimatedTokens)
                .build();
    }

    // ── Inner Exceptions ─────────────────────────────────────────────────────

    public static final class ContextSizeExceededException extends RuntimeException {
        public ContextSizeExceededException(String message) {
            super(message);
        }
    }

    public static final class ContextSerializationException extends RuntimeException {
        public ContextSerializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
