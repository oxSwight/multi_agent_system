package com.midas.d3.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.midas.d3.build.BuildReport;

import java.util.List;
import java.util.Objects;

/**
 * Scores a generated artifact set <b>deterministically</b> — no LLM in the loop — by gating a
 * {@link BuildReport} build signal against a {@link Rubric} of static checks.
 *
 * <p>This is the measurement substrate of Phase 4 (Closed-Loop Quality / P4-A1). It gives MIDAS the
 * feedback loop it has been missing: a reproducible, regression-guardable quality number for the
 * software the pipeline produces, so a later change (model swap, tier-down, prompt edit) can be
 * proven not to have degraded output — not just that the engine's own unit tests still pass.
 *
 * <p>Live execution of golden cases through the real pipeline is a later increment (P4-A2); this
 * class is intentionally pure so the scoring logic itself is CI-stable and free.
 */
public final class QualityEvalHarness {

    private QualityEvalHarness() {
    }

    /**
     * @param artifacts generated path→contents object (a merged source/test map); may be null
     * @param build     the build outcome for {@code artifacts}; null is treated as a failed build
     * @param rubric    the deterministic checks to apply
     * @return the quality verdict
     */
    public static QualityScore score(JsonNode artifacts, BuildReport build, Rubric rubric) {
        Objects.requireNonNull(rubric, "rubric");
        // Two-phase signal: compiled() is true for a green build OR a TEST-only failure; testsPassed()
        // only for a fully green build. A null build (verification never ran) reads as the worst tier.
        boolean compiled = build != null && build.compiled();
        boolean testsPassed = build != null && build.testsPassed();
        Rubric.Result result = rubric.evaluate(artifacts);
        return new QualityScore(compiled, testsPassed, result.score(), result.violations());
    }

    /**
     * A reference case for regression-gating: a rubric plus the minimum {@link QualityScore#overall()}
     * a passing pipeline run must achieve for this idea. Running golden cases through the real
     * pipeline and asserting the threshold in CI is P4-A2.
     */
    public record GoldenCase(String id, String description, Rubric rubric, double minOverall) {
        public GoldenCase {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(rubric, "rubric");
        }

        /** True when {@code score} meets this case's regression threshold. */
        public boolean isSatisfiedBy(QualityScore score) {
            return score.overall() >= minOverall;
        }
    }

    /**
     * The built-in golden corpus. Deliberately small and conservative — it grows as P4-A2 wires live
     * pipeline runs through the harness. Two seed cases anchor the two dominant shapes the pipeline
     * emits: a buildable REST service and a buildable CLI tool. Both demand test sources and forbid a
     * {@code System.exit} call — a generated exit would terminate the sandbox JVM that executes the
     * code, so exit status must flow through return codes instead.
     */
    public static List<GoldenCase> defaultGoldenCases() {
        // Case 1 — a REST CRUD service: buildable, exposes a controller, ships tests, and carries no
        // hardcoded credentials nor a sandbox-killing System.exit.
        Rubric restCrudApi = new Rubric("rest-crud-api", List.of(
                RubricRule.requirePath("pom.xml"),
                RubricRule.requireContent(".java", "rest-controller", "(?i)@RestController|@Controller"),
                RubricRule.requirePath("Test.java"),
                RubricRule.forbidContent("hardcoded-password", "(?i)password\\s*[:=]\\s*[\"'][^\"']+[\"']"),
                RubricRule.forbidContent("system-exit", "(?i)System\\.exit")
        ));

        // Case 2 — a command-line tool: buildable, has a main entry point, ships tests, and signals its
        // exit status via return codes rather than a System.exit that would kill the sandbox JVM.
        Rubric cliTool = new Rubric("cli-tool", List.of(
                RubricRule.requirePath("pom.xml"),
                RubricRule.requireContent(".java", "main-method", "(?i)public\\s+static\\s+void\\s+main"),
                RubricRule.requirePath("Test.java"),
                RubricRule.forbidContent("system-exit", "(?i)System\\.exit")
        ));

        return List.of(
                new GoldenCase(
                        "rest-crud-api",
                        "A buildable REST CRUD service exposing a controller and tests, with no hardcoded "
                                + "credentials and no sandbox-killing System.exit.",
                        restCrudApi,
                        1.0),
                new GoldenCase(
                        "cli-tool",
                        "A buildable command-line tool with a main entry point and tests, signaling exit "
                                + "status via return codes rather than a sandbox-killing System.exit.",
                        cliTool,
                        1.0));
    }
}
