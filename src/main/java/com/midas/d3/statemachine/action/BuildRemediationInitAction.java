package com.midas.d3.statemachine.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.midas.d3.context.AuditEntry;
import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.AgentDispatcher;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import com.midas.d3.statemachine.PipelineTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Fires on the {@code BUILD_CHOICE → CODE_GENERATION} self-healing branch: turns the failed
 * build report into a structured {@code remediationDirective} (carrying the compiler
 * diagnostics) so the implementation agent's next pass has the exact errors to fix, increments
 * the build-remediation counter, and re-drives code generation in auto-mode.
 *
 * <p>The directive is consumed via the {@code IMPLEMENTATION_ENGINEER} context-reducer
 * dependency on {@code remediationDirective}, so it reaches the code agent without any other
 * routing change. SecOps artifacts (if any) are cleared so they are regenerated against the
 * fixed code.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuildRemediationInitAction implements Action<MidasState, MidasEvent> {

    private final ObjectMapper objectMapper;
    private final PipelineTopology topology;
    private final AgentDispatcher agentDispatcher;

    @Override
    public void execute(StateContext<MidasState, MidasEvent> context) {
        Map<Object, Object> vars = context.getExtendedState().getVariables();

        MidasContext current = (MidasContext) vars.get(PipelineContextKeys.MIDAS_CONTEXT);
        JsonNode report = (JsonNode) vars.get(PipelineContextKeys.LAST_VALIDATED_NODE);

        if (current == null) {
            log.error("[BuildRemediationInitAction] MidasContext missing — cannot initiate build remediation.");
            return;
        }
        if (report == null) {
            log.error("[BuildRemediationInitAction] LAST_VALIDATED_NODE missing — cannot initiate build remediation.");
            return;
        }

        int nextAttempt = current.getBuildRemediationAttempts() + 1;
        JsonNode directive = buildDirective(report, nextAttempt);

        MidasContext remediated = current
                .withBuildReport(report)
                .withRemediationDirective(directive)
                .withBuildRemediationAttempts(nextAttempt)
                .withValidationRetries(0)
                .withSecOpsArtifacts(null)
                .appendAudit(AuditEntry.warn(
                        MidasState.BUILD_VERIFICATION.name(),
                        "Build failed — self-healing remediation initiated",
                        "Build remediation loop " + nextAttempt + "/" + topology.maxBuildRemediations()
                                + " → CODE_GENERATION"));

        vars.put(PipelineContextKeys.MIDAS_CONTEXT, remediated);
        vars.remove(PipelineContextKeys.LAST_VALIDATED_NODE);
        vars.remove(PipelineContextKeys.LAST_VALIDATION_ERROR);

        log.warn("[BuildRemediationInitAction] Run [{}] build remediation {}/{} → CODE_GENERATION",
                current.getPipelineRunId(), nextAttempt, topology.maxBuildRemediations());

        agentDispatcher.dispatchIfAutoMode(context.getStateMachine(), MidasState.CODE_GENERATION);
    }

    /** Builds the directive injected into the next implementation pass. */
    private JsonNode buildDirective(JsonNode report, int attempt) {
        ObjectNode directive = objectMapper.createObjectNode();
        directive.put("type", "BUILD_FAILURE");
        directive.put("remediation_attempt", attempt);
        directive.put("instruction",
                "The previous generation FAILED to build. Fix the exact compiler/build errors "
                        + "below without changing the intended behavior. Return the corrected, "
                        + "fully-compiling source.");
        directive.put("build_tool", report.path("tool").asText("UNKNOWN"));
        directive.set("diagnostics", copyDiagnostics(report));
        // A compact tail of raw build output as a fallback when diagnostics couldn't be parsed.
        directive.put("raw_build_output", report.path("raw_output_tail").asText(""));
        return directive;
    }

    private ArrayNode copyDiagnostics(JsonNode report) {
        ArrayNode out = objectMapper.createArrayNode();
        JsonNode diagnostics = report.get("diagnostics");
        if (diagnostics != null && diagnostics.isArray()) {
            diagnostics.forEach(out::add);
        }
        return out;
    }
}
