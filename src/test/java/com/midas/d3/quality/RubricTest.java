package com.midas.d3.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Rubric / RubricRule")
class RubricTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode artifacts(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("requirePath matches by path suffix; reports a violation when absent")
    void requirePath() {
        JsonNode a = artifacts("{\"src/main/java/App.java\":\"x\",\"pom.xml\":\"<project/>\"}");
        assertThat(RubricRule.requirePath("pom.xml").evaluate(a)).isEmpty();
        assertThat(RubricRule.requirePath("build.gradle").evaluate(a)).isPresent();
    }

    @Test
    @DisplayName("forbidContent flags a hardcoded secret and passes on clean code")
    void forbidContent() {
        RubricRule rule = RubricRule.forbidContent("hardcoded-password",
                "(?i)password\\s*[:=]\\s*[\"'][^\"']+[\"']");
        assertThat(rule.evaluate(artifacts("{\"A.java\":\"String password = \\\"hunter2\\\";\"}"))).isPresent();
        assertThat(rule.evaluate(artifacts("{\"A.java\":\"String pwd = env(\\\"PWD\\\");\"}"))).isEmpty();
    }

    @Test
    @DisplayName("requireContent passes only when a matching-suffix file contains the pattern")
    void requireContent() {
        RubricRule rule = RubricRule.requireContent(".java", "controller", "(?i)@RestController");
        assertThat(rule.evaluate(artifacts("{\"web/Api.java\":\"@RestController class Api{}\"}"))).isEmpty();
        assertThat(rule.evaluate(artifacts("{\"web/Api.java\":\"class Api{}\"}"))).isPresent();
    }

    @Test
    @DisplayName("evaluate computes the satisfied fraction and collects violations")
    void rubricEvaluate() {
        Rubric rubric = new Rubric("t", List.of(
                RubricRule.requirePath("pom.xml"),       // satisfied
                RubricRule.requirePath("missing.txt"))); // violated
        Rubric.Result r = rubric.evaluate(artifacts("{\"pom.xml\":\"x\"}"));
        assertThat(r.score()).isEqualTo(0.5);
        assertThat(r.violations()).hasSize(1);
    }

    @Test
    @DisplayName("an empty rubric is vacuously satisfied (score 1.0)")
    void emptyRubric() {
        assertThat(new Rubric("empty", List.of()).evaluate(artifacts("{}")).score()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("non-object artifacts produce a violation, not an exception")
    void nonObjectArtifacts() {
        assertThat(RubricRule.requirePath("pom.xml").evaluate(mapper.createArrayNode())).isPresent();
    }
}
