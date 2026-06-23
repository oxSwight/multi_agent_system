package com.midas.d3.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.build.BuildReport;
import com.midas.d3.build.BuildTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("QualityEvalHarness")
class QualityEvalHarnessTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode artifacts(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BuildReport pass() {
        return BuildReport.success(BuildTool.MAVEN, "ok");
    }

    private BuildReport fail() {
        return BuildReport.failure(BuildTool.MAVEN, 1, List.of(), "compile failed", "");
    }

    private Rubric requirePom() {
        return new Rubric("r", List.of(RubricRule.requirePath("pom.xml")));
    }

    @Test
    @DisplayName("build pass + satisfied rubric → overall 1.0")
    void perfectScore() {
        QualityScore s = QualityEvalHarness.score(artifacts("{\"pom.xml\":\"x\"}"), pass(), requirePom());
        assertThat(s.buildPassed()).isTrue();
        assertThat(s.rubricScore()).isEqualTo(1.0);
        assertThat(s.overall()).isEqualTo(1.0);
        assertThat(s.rubricViolations()).isEmpty();
    }

    @Test
    @DisplayName("a failed build hard-gates overall to 0 even with a perfect rubric")
    void buildFailureGatesToZero() {
        QualityScore s = QualityEvalHarness.score(artifacts("{\"pom.xml\":\"x\"}"), fail(), requirePom());
        assertThat(s.rubricScore()).isEqualTo(1.0);
        assertThat(s.overall()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("a null build is treated as a failed build")
    void nullBuildIsFailure() {
        QualityScore s = QualityEvalHarness.score(artifacts("{\"pom.xml\":\"x\"}"), null, requirePom());
        assertThat(s.buildPassed()).isFalse();
        assertThat(s.overall()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("rubric violations lower the score proportionally")
    void partialRubric() {
        Rubric two = new Rubric("two", List.of(
                RubricRule.requirePath("pom.xml"), RubricRule.requirePath("nope")));
        QualityScore s = QualityEvalHarness.score(artifacts("{\"pom.xml\":\"x\"}"), pass(), two);
        assertThat(s.rubricScore()).isEqualTo(0.5);
        assertThat(s.overall()).isEqualTo(0.5);
        assertThat(s.rubricViolations()).hasSize(1);
    }

    @Test
    @DisplayName("toJson exposes the verdict shape")
    void json() {
        JsonNode j = QualityEvalHarness.score(artifacts("{\"pom.xml\":\"x\"}"), pass(), requirePom()).toJson(mapper);
        assertThat(j.get("build_passed").asBoolean()).isTrue();
        assertThat(j.get("overall").asDouble()).isEqualTo(1.0);
        assertThat(j.has("rubric_violations")).isTrue();
    }

    @Test
    @DisplayName("default golden case: a clean REST service satisfies it; a flawed, non-building one does not")
    void defaultGoldenCase() {
        QualityEvalHarness.GoldenCase golden = QualityEvalHarness.defaultGoldenCases().get(0);

        JsonNode good = artifacts("{\"pom.xml\":\"<project/>\","
                + "\"src/main/java/web/Api.java\":\"@RestController class Api{ String x = env(); }\"}");
        assertThat(golden.isSatisfiedBy(QualityEvalHarness.score(good, pass(), golden.rubric()))).isTrue();

        // No controller annotation + a hardcoded password + a failing build → fails the gate.
        JsonNode bad = artifacts("{\"pom.xml\":\"<project/>\","
                + "\"src/main/java/web/Api.java\":\"class Api{ String password = \\\"hunter2\\\"; }\"}");
        assertThat(golden.isSatisfiedBy(QualityEvalHarness.score(bad, fail(), golden.rubric()))).isFalse();
    }
}
