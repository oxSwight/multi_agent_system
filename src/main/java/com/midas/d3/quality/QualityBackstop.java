package com.midas.d3.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.build.BuildReportJson;
import com.midas.d3.build.SourceMaps;

/**
 * The deterministic predicate behind the quality backstop: does the generated artifact carry
 * SUBSTANTIVE proof that it meets its requirements — strong enough to override a (flaky) LLM
 * Controller REJECT?
 *
 * <p>"Substantive" is deliberately strict, to never rubber-stamp a vacuous score: there must be a
 * NON-EMPTY gated rubric ({@link SpecRubricBuilder} — the domain floor / model {@code must_exist} for
 * the detected shape) that the artifact FULLY satisfies, AND the build must not have failed. A shape
 * with no gated criteria (so the rubric would be vacuously 1.0) does NOT qualify — its score proves
 * nothing. This is the guard against the observed failure mode where a skipped build + an empty rubric
 * yields {@code overall=1.0} with no real evidence.
 */
public final class QualityBackstop {

    private QualityBackstop() {
    }

    public static boolean qualifies(JsonNode technicalSpec,
                                    JsonNode generatedSourceCode,
                                    JsonNode generatedTests,
                                    JsonNode buildReport,
                                    ObjectMapper mapper) {
        Rubric rubric = SpecRubricBuilder.fromSpec(technicalSpec);
        if (rubric.rules().isEmpty()) {
            return false; // no gated criteria → the score would be vacuous → never backstop
        }
        JsonNode artifacts = SourceMaps.merge(generatedSourceCode, generatedTests, mapper);
        if (!rubric.evaluate(artifacts).violations().isEmpty()) {
            return false; // not every gated criterion is satisfied
        }
        // The build must not have failed. A skipped/successful build clears this — the same bar the
        // normal pass path clears; a real compile/test failure is never backstopped.
        return BuildReportJson.read(buildReport).testsPassed();
    }
}
