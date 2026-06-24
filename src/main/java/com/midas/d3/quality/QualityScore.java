package com.midas.d3.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic quality verdict for one generated artifact set.
 *
 * <p>The build signal is <b>three-tier</b>, mirroring the two-phase {@code BuildReport}
 * (compile → test) rather than a single pass/fail bit:
 * <ul>
 *   <li><b>did not compile</b> — a structural defect; quality is 0 (hard floor). A high rubric
 *       score on un-buildable code must never read as "good."</li>
 *   <li><b>compiled but tests fail</b> — structurally valid, behaviorally unverified; it earns
 *       partial build credit ({@link #COMPILED_BUT_TESTS_FAILED_CREDIT}) instead of being scored
 *       identically to code that won't even compile.</li>
 *   <li><b>compiled and tests pass</b> — full build credit.</li>
 * </ul>
 * The graded build signal is the {@link #testsPassScore()} sub-score; {@link #overall()} is the
 * rubric satisfied-fraction gated by it, so {@code overall} still collapses to 0 for un-buildable
 * output while distinguishing "tests fail" from "won't compile."
 */
public record QualityScore(boolean compiled, boolean testsPassed, double rubricScore,
                           List<String> rubricViolations) {

    /**
     * Build credit for code that compiles but whose tests fail. A milestone (it is structurally
     * valid) but unverified behavior — half credit keeps it strictly above non-compiling output and
     * strictly below a green build.
     */
    public static final double COMPILED_BUT_TESTS_FAILED_CREDIT = 0.5;

    public QualityScore {
        rubricViolations = (rubricViolations == null) ? List.of() : List.copyOf(rubricViolations);
        rubricScore = Math.max(0.0, Math.min(1.0, rubricScore));
        // Invariant: a build cannot pass its tests without first compiling. Normalize an
        // inconsistent (testsPassed && !compiled) pair so the tiers can never contradict.
        compiled = compiled || testsPassed;
    }

    /**
     * Graded build sub-score in [0,1] derived from the two build phases:
     * 0 when the sources did not compile, {@link #COMPILED_BUT_TESTS_FAILED_CREDIT} when they
     * compiled but the tests failed, and 1 when the tests passed.
     */
    public double testsPassScore() {
        if (!compiled) {
            return 0.0;
        }
        return testsPassed ? 1.0 : COMPILED_BUT_TESTS_FAILED_CREDIT;
    }

    /** Full build success — compiled <i>and</i> its tests passed. The strict build gate. */
    public boolean buildPassed() {
        return testsPassed;
    }

    /** Overall quality in [0,1]: the rubric fraction gated by the {@link #testsPassScore()}. */
    public double overall() {
        return testsPassScore() * rubricScore;
    }

    public JsonNode toJson(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        ObjectNode root = mapper.createObjectNode();
        root.put("compiled", compiled);
        root.put("tests_passed", testsPassed);
        root.put("build_passed", buildPassed());
        root.put("tests_pass_score", testsPassScore());
        root.put("rubric_score", rubricScore);
        root.put("overall", overall());
        ArrayNode violations = root.putArray("rubric_violations");
        rubricViolations.forEach(violations::add);
        return root;
    }
}
