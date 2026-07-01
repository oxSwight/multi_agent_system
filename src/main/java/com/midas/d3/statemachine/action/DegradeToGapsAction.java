package com.midas.d3.statemachine.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.midas.d3.context.AuditEntry;
import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Transition action for {@code CODE_GENERATION → COMPLETED_WITH_GAPS} (graceful degradation).
 *
 * <p>Fires on {@link MidasEvent#DEGRADE_ARTIFACT}, which the {@link com.midas.d3.statemachine.AgentDispatcher}
 * sends only when CODE_GENERATION hit an unhealable functional gap AND a best-effort partial artifact is
 * salvageable AND degradation is enabled. This action:
 * <ol>
 *   <li>stores the partial {@code source_files} + {@code feature_manifest} into the context, so the
 *       delivered ZIP actually contains code;</li>
 *   <li>builds an honest {@code coverageReport} — what was delivered vs. the specific unmet gaps, with
 *       {@code build_verified=false} (the build gate never ran on a partial artifact);</li>
 *   <li>flags {@link PipelineContextKeys#DEGRADED_COMPLETION} so the shared
 *       {@link PipelineCompletionAction} finalizes the run as {@code COMPLETED_WITH_GAPS}.</li>
 * </ol>
 *
 * <p>It does not package or deliver — the {@code COMPLETED_WITH_GAPS} state-entry action
 * ({@link PipelineCompletionAction}) does, reusing the exact same packaging/idempotency path as a
 * clean completion.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DegradeToGapsAction implements Action<MidasState, MidasEvent> {

    private final ObjectMapper objectMapper;

    @Override
    @SuppressWarnings("unchecked")
    public void execute(StateContext<MidasState, MidasEvent> context) {
        Map<Object, Object> vars = context.getExtendedState().getVariables();

        MidasContext current = (MidasContext) vars.get(PipelineContextKeys.MIDAS_CONTEXT);
        if (current == null) {
            log.error("[DegradeToGapsAction] MidasContext missing — cannot deliver partial artifact.");
            return;
        }

        JsonNode partialSource   = (JsonNode) vars.get(PipelineContextKeys.DEGRADATION_PARTIAL_SOURCE);
        JsonNode featureManifest = (JsonNode) vars.get(PipelineContextKeys.DEGRADATION_FEATURE_MANIFEST);
        List<String> gaps = vars.get(PipelineContextKeys.DEGRADATION_GAPS) instanceof List<?> l
                ? (List<String>) l : List.of();

        int deliveredFiles = partialSource != null && partialSource.isObject() ? partialSource.size() : 0;
        JsonNode coverageReport = buildCoverageReport(partialSource, featureManifest, gaps, deliveredFiles);

        MidasContext degraded = current
                .withGeneratedSourceCode(partialSource)
                .withFeatureManifest(featureManifest)
                .withCoverageReport(coverageReport)
                .withLastErrorMessage(null)              // not an error — a delivered, degraded product
                .withValidationRetries(0)
                .appendAudit(AuditEntry.warn(
                        MidasState.CODE_GENERATION.name(),
                        "Delivered a partial artifact (COMPLETED_WITH_GAPS) — unhealable functional gap",
                        gaps.isEmpty() ? "see coverage report" : String.join("; ", gaps)));

        vars.put(PipelineContextKeys.MIDAS_CONTEXT, degraded);
        vars.put(PipelineContextKeys.DEGRADED_COMPLETION, Boolean.TRUE);

        // Consume the transient payload so a reused machine cannot leak it into a later run.
        vars.remove(PipelineContextKeys.DEGRADATION_PARTIAL_SOURCE);
        vars.remove(PipelineContextKeys.DEGRADATION_FEATURE_MANIFEST);
        vars.remove(PipelineContextKeys.DEGRADATION_GAPS);
        vars.remove(PipelineContextKeys.LAST_VALIDATED_NODE);
        vars.remove(PipelineContextKeys.LAST_VALIDATION_ERROR);

        log.warn("[DegradeToGapsAction] run=[{}] delivering COMPLETED_WITH_GAPS — {} file(s), {} gap(s).",
                current.getPipelineRunId(), deliveredFiles, gaps.size());
    }

    /**
     * Builds the honest coverage report. Reuses the Controller {@code coverage_matrix} concept: each
     * capability the partial manifest names is {@code GENERATED}; each unmet gap is {@code MISSING}.
     */
    private JsonNode buildCoverageReport(JsonNode partialSource, JsonNode featureManifest,
                                         List<String> gaps, int deliveredFiles) {
        ObjectNode report = objectMapper.createObjectNode();
        report.put("status", MidasState.COMPLETED_WITH_GAPS.name());
        report.put("build_verified", false);
        report.put("delivered_file_count", deliveredFiles);

        List<String> capabilities = capabilityNames(featureManifest);
        ArrayNode delivered = report.putArray("delivered_capabilities");
        capabilities.forEach(delivered::add);

        ArrayNode gapsArray = report.putArray("gaps");
        gaps.forEach(gapsArray::add);

        ArrayNode matrix = report.putArray("coverage_matrix");
        for (String capability : capabilities) {
            ObjectNode row = matrix.addObject();
            row.put("capability", capability);
            row.put("status", "GENERATED");
        }
        for (String gap : gaps) {
            ObjectNode row = matrix.addObject();
            row.put("gap", gap);
            row.put("status", "MISSING");
        }

        report.put("summary",
                "Delivered a best-effort partial artifact (" + deliveredFiles + " file(s)). "
                        + "The listed gaps could not be completed within the generation budget, and the "
                        + "build was NOT verified. Review the code and the gaps before use.");
        return report;
    }

    /** Best-effort extraction of capability/feature names from a feature manifest (array or object). */
    private List<String> capabilityNames(JsonNode featureManifest) {
        List<String> names = new ArrayList<>();
        if (featureManifest == null || featureManifest.isNull()) {
            return names;
        }
        if (featureManifest.isArray()) {
            for (JsonNode entry : featureManifest) {
                String name = firstNonBlank(entry, "feature_name", "feature_id", "name", "id");
                if (name != null) names.add(name);
            }
        } else if (featureManifest.isObject()) {
            featureManifest.fieldNames().forEachRemaining(names::add);
        }
        return names;
    }

    private static String firstNonBlank(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode v = node.get(field);
            if (v != null && v.isTextual() && !v.asText().isBlank()) {
                return v.asText().strip();
            }
        }
        return null;
    }
}
