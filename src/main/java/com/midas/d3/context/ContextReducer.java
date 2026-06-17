package com.midas.d3.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
                // Integration stage is skipped for self-contained products (no external services),
                // so its artifact is soft: omit it gracefully rather than failing the build.
                ArtifactDependency.optional("integrationStrategy")));
        ARTIFACT_DEPENDENCIES.put(AgentRole.QA_ENGINEER, List.of(
                ArtifactDependency.required("technicalSpec"),
                ArtifactDependency.required("architectureDesign"),
                ArtifactDependency.required("generatedSourceCode")));
        ARTIFACT_DEPENDENCIES.put(AgentRole.SECOPS_ENGINEER, List.of(
                ArtifactDependency.required("technicalSpec"),
                ArtifactDependency.required("architectureDesign"),
                ArtifactDependency.required("generatedSourceCode"),
                ArtifactDependency.required("generatedTests")));
        // The Controller is the blocking Product-Owner gate. To keep it well under token limits
        // it judges intent conformance from the original spec (business_goal + requested features)
        // and the SecOps artifacts (which carry the release_artifacts map of what was actually
        // shipped) — deliberately NOT the full raw source/test bodies. Both are produced by stages
        // that always run on the path to this gate, so both are required (fail-fast if absent).
        ARTIFACT_DEPENDENCIES.put(AgentRole.CONTROLLER, List.of(
                ArtifactDependency.required("technicalSpec"),
                ArtifactDependency.required("secOpsArtifacts")));
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

    // ── Private Helpers ──────────────────────────────────────────────────────

    private JsonNode resolveArtifact(MidasContext ctx, String key) {
        return switch (key) {
            case "technicalSpec"      -> ctx.getTechnicalSpec();
            case "architectureDesign" -> ctx.getArchitectureDesign();
            case "integrationStrategy"-> ctx.getIntegrationStrategy();
            case "generatedSourceCode"-> ctx.getGeneratedSourceCode();
            case "generatedTests"     -> ctx.getGeneratedTests();
            case "secOpsArtifacts"    -> ctx.getSecOpsArtifacts();
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
