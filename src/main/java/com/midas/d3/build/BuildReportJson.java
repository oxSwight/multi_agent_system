package com.midas.d3.build;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Reconstructs the build-signal fields of a {@link BuildReport} from its canonical JSON
 * ({@code build_status} + optional {@code failure_phase}) — enough for a consumer that only needs the
 * derived {@link BuildReport#compiled()} / {@link BuildReport#testsPassed()} tiers (e.g. the runtime
 * quality score). Keeping the compile/test-tier logic in {@code BuildReport} means there is one source
 * of truth; this adapter only revives the two inputs that logic reads.
 */
public final class BuildReportJson {

    private BuildReportJson() {
    }

    /**
     * @return a minimal {@link BuildReport} carrying the report's success + failure-phase signal, or a
     * null-safe failed report when {@code node} is absent/unparseable (a missing report reads as the
     * worst tier, matching {@code QualityEvalHarness}'s null-build handling).
     */
    public static BuildReport read(JsonNode node) {
        if (node == null || !node.isObject()) {
            return BuildReport.failure(BuildTool.NONE, -1, List.of(), "no build report", "");
        }
        boolean success = "SUCCESS".equalsIgnoreCase(text(node, "build_status"));
        if (success) {
            return BuildReport.success(BuildTool.NONE, text(node, "summary"));
        }
        BuildPhase phase = "TEST".equalsIgnoreCase(text(node, "failure_phase"))
                ? BuildPhase.TEST
                : BuildPhase.COMPILE;
        return BuildReport.failure(BuildTool.NONE, -1, List.of(), text(node, "summary"), "", phase);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText().strip() : "";
    }
}
