package com.midas.d3.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.midas.d3.context.MidasContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs the {@code BUILD_VERIFICATION} stage: it merges the generated source and test maps, detects
 * every verifiable {@link BuildSurface}, materializes everything into a disposable
 * {@link SandboxWorkspace}, and verifies each surface on its own terms — a real build for toolchain
 * surfaces, a deterministic structural check for MV3 extensions. The per-surface results are
 * aggregated into the single canonical {@link BuildReport} JSON the state machine validates and
 * routes on.
 *
 * <h2>Per-surface, not first-match</h2>
 * The old detector returned a single toolchain on first match, so a hybrid project was verified only
 * on its backend ({@code pom.xml} wins) and a pure-JS extension resolved to {@code NONE} and was
 * skipped. Detecting and verifying every surface closes that false-PASS: the frontend extension is
 * gated structurally even when a backend {@code pom.xml} is present, and a toolchain-less extension
 * is a real gate rather than a fail-open skip.
 *
 * <h2>Fail-open vs fail-closed</h2>
 * <ul>
 *   <li><b>No recognized surface</b> → {@link BuildReport#skipped} (a static bundle is not a failure).</li>
 *   <li><b>Toolchain unavailable / timeout</b> for a surface → that surface is skipped (non-blocking)
 *       so an environmental gap never wedges the pipeline, but it is recorded loudly.</li>
 *   <li><b>Real compile/structural failure</b> on any surface → {@code FAILED}: the signal that drives
 *       the self-healing loop back into code generation.</li>
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
        List<BuildSurface> surfaces = SurfaceDetector.detect(merged, objectMapper);
        if (surfaces.isEmpty()) {
            log.info("[BuildVerificationService] Run [{}] — no recognized project surface; skipping verification.",
                    context.getPipelineRunId());
            return BuildReport.skipped("No recognized project surface (Maven / Gradle / npm / MV3 extension) "
                    + "in the generated project — build verification not applicable.");
        }

        try (SandboxWorkspace workspace = SandboxWorkspace.create(context.getPipelineRunId())) {
            int files = workspace.materialize(merged);
            if (files == 0) {
                return BuildReport.skipped("Generated source map produced no materializable files.");
            }
            log.info("[BuildVerificationService] Run [{}] — verifying {} file(s) across {} surface(s): {}.",
                    context.getPipelineRunId(), files, surfaces.size(),
                    surfaces.stream().map(BuildSurface::label).toList());

            List<BuildReport> reports = new ArrayList<>(surfaces.size());
            for (BuildSurface surface : surfaces) {
                reports.add(verifySurface(surface, workspace.root(), merged, context.getPipelineRunId()));
            }
            // A single surface passes through verbatim (tool, summary, diagnostics preserved);
            // multiple surfaces are folded into one canonical report.
            return reports.size() == 1 ? reports.get(0) : aggregate(reports, merged);

        } catch (IOException e) {
            log.warn("[BuildVerificationService] Run [{}] — sandbox setup failed: {}",
                    context.getPipelineRunId(), e.getMessage());
            return BuildReport.skipped("Build verification sandbox setup failed: " + e.getMessage());
        }
    }

    private BuildReport verifySurface(BuildSurface surface, Path sandboxRoot, JsonNode merged, String runId) {
        if (!surface.kind().isToolchain()) {
            BuildReport report = ExtensionStructureVerifier.verify(merged, surface.rootDir(), objectMapper);
            log.info("[BuildVerificationService] Run [{}] — {} structural verification: {}.",
                    runId, surface.label(), report.buildStatus());
            return report;
        }

        Path surfaceRoot = surface.rootDir().isEmpty()
                ? sandboxRoot
                : sandboxRoot.resolve(surface.rootDir()).normalize();
        try {
            log.info("[BuildVerificationService] Run [{}] — building {} with {}.",
                    runId, surface.label(), surface.kind().toolchain());
            BuildReport report = buildExecutor.execute(surfaceRoot, surface.kind().toolchain());
            // Attach the offending source snippet to each attributable diagnostic so the self-healing
            // loop feeds the model the exact broken code rather than coordinates buried in raw output.
            return BuildSnippetExtractor.enrich(report, merged);
        } catch (BuildExecutionException e) {
            // Environmental gap (toolchain absent / timeout): fail-open for this surface so the
            // pipeline is not wedged by infrastructure, but record it prominently.
            log.warn("[BuildVerificationService] Run [{}] — {} verification could not run: {}",
                    runId, surface.label(), e.getMessage());
            return BuildReport.skipped("Build verification could not run: " + e.getMessage());
        }
    }

    /**
     * Folds multiple per-surface reports into one canonical report: any failing surface makes the
     * whole verification FAILED (diagnostics merged across failures); otherwise SUCCESS, reporting the
     * first real toolchain among the passing surfaces.
     */
    private BuildReport aggregate(List<BuildReport> reports, JsonNode merged) {
        List<BuildReport> failures = reports.stream().filter(r -> !r.success()).toList();
        if (!failures.isEmpty()) {
            List<BuildDiagnostic> diagnostics = failures.stream()
                    .flatMap(r -> r.diagnostics().stream())
                    .toList();
            BuildReport primary = failures.get(0);
            BuildReport aggregated = BuildReport.failure(
                    primary.tool(), primary.exitCode(), diagnostics,
                    failures.size() + " of " + reports.size() + " surface(s) failed verification.",
                    primary.rawOutputTail(), primary.failurePhase());
            return BuildSnippetExtractor.enrich(aggregated, merged);
        }
        BuildTool tool = reports.stream()
                .map(BuildReport::tool)
                .filter(t -> t != BuildTool.NONE)
                .findFirst()
                .orElse(BuildTool.NONE);
        return BuildReport.success(tool, reports.size() + " surface(s) verified.");
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
