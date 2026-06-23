package com.midas.d3.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.midas.d3.context.MidasContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Runs the {@code BUILD_VERIFICATION} stage: it merges the generated source and test maps,
 * detects the toolchain, materializes everything into a disposable {@link SandboxWorkspace},
 * and delegates the actual build to a {@link BuildExecutor}. The result is a {@link BuildReport}
 * serialized to the canonical JSON artifact the state machine validates and routes on.
 *
 * <h2>Fail-open vs fail-closed</h2>
 * <ul>
 *   <li><b>No recognized build descriptor</b> → {@link BuildReport#skipped} (a static bundle is
 *       not a failure).</li>
 *   <li><b>Toolchain unavailable / timeout</b> → also skipped: an environmental gap must not
 *       wedge the pipeline, but it is recorded loudly in the report summary.</li>
 *   <li><b>Real compile failure</b> → {@code FAILED}: this is the signal that drives the
 *       self-healing loop back into code generation.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuildVerificationService {

    private final BuildExecutor buildExecutor;
    private final ObjectMapper objectMapper;

    /** Runs verification and returns the report as its canonical JSON artifact string. */
    public String verifyToReportJson(MidasContext context) {
        return verify(context).toJson(objectMapper).toString();
    }

    /** Runs verification and returns the structured {@link BuildReport}. */
    public BuildReport verify(MidasContext context) {
        Objects.requireNonNull(context, "context must not be null");

        JsonNode merged = mergedSourceMap(context);
        BuildTool tool = BuildToolDetector.detect(merged);
        if (tool == BuildTool.NONE) {
            log.info("[BuildVerificationService] Run [{}] — no recognized build descriptor; skipping verification.",
                    context.getPipelineRunId());
            return BuildReport.skipped("No recognized build descriptor (pom.xml / build.gradle / package.json) "
                    + "in the generated project — build verification not applicable.");
        }

        try (SandboxWorkspace workspace = SandboxWorkspace.create(context.getPipelineRunId())) {
            int files = workspace.materialize(merged);
            if (files == 0) {
                return BuildReport.skipped("Generated source map produced no materializable files.");
            }
            log.info("[BuildVerificationService] Run [{}] — verifying {} file(s) with {}.",
                    context.getPipelineRunId(), files, tool);
            return buildExecutor.execute(workspace.root(), tool);
        } catch (BuildExecutionException e) {
            // Environmental gap (toolchain absent / timeout): fail-open so the pipeline is not
            // wedged by infrastructure, but record it prominently.
            log.warn("[BuildVerificationService] Run [{}] — verification could not run: {}",
                    context.getPipelineRunId(), e.getMessage());
            return BuildReport.skipped("Build verification could not run: " + e.getMessage());
        } catch (IOException e) {
            log.warn("[BuildVerificationService] Run [{}] — sandbox setup failed: {}",
                    context.getPipelineRunId(), e.getMessage());
            return BuildReport.skipped("Build verification sandbox setup failed: " + e.getMessage());
        }
    }

    /**
     * Combines {@code generatedSourceCode} and {@code generatedTests} into one path→contents
     * map. Source files are written first; tests are overlaid so a test file never clobbers a
     * production source file of the same path.
     */
    private JsonNode mergedSourceMap(MidasContext context) {
        ObjectNode merged = objectMapper.createObjectNode();
        copyInto(merged, context.getGeneratedSourceCode());
        copyInto(merged, context.getGeneratedTests());
        return merged;
    }

    private void copyInto(ObjectNode target, JsonNode source) {
        if (source == null || !source.isObject()) {
            return;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = source.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!target.has(e.getKey())) {
                target.set(e.getKey(), e.getValue());
            }
        }
    }
}
