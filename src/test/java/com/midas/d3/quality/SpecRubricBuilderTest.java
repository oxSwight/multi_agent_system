package com.midas.d3.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SpecRubricBuilder")
class SpecRubricBuilderTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new JacksonConfig().objectMapper();
    }

    @Test
    @DisplayName("model must_contain criteria are excluded (advisory) — a must_contain-only spec yields a vacuous rubric")
    void mustContainOnly_yieldsEmptyRubric() throws Exception {
        JsonNode spec = mapper.readTree("""
                {"business_goal":"task api",
                 "runtime_environment":{"deployment_target":"CLOUD_SERVICE","execution_model":"SERVER_SIDE"},
                 "core_features":[{"id":"f","name":"F",
                   "acceptance_criteria":[{"id":"c","description":"create","must_contain":"@PostMapping"}]}]}
                """);

        Rubric rubric = SpecRubricBuilder.fromSpec(spec);

        assertThat(rubric.rules()).isEmpty();
        // vacuous rubric scores 1.0 regardless of source — advisory regexes never drag the score down
        assertThat(rubric.evaluate(mapper.readTree("{\"X.java\":\"nothing\"}")).score()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("model must_exist criteria are gated — included and evaluated against file presence")
    void mustExist_isIncludedAndGated() throws Exception {
        JsonNode spec = mapper.readTree("""
                {"business_goal":"task api",
                 "runtime_environment":{"deployment_target":"CLOUD_SERVICE","execution_model":"SERVER_SIDE"},
                 "core_features":[{"id":"f","name":"F",
                   "acceptance_criteria":[{"id":"ctrl","description":"controller","must_exist":"TaskController.java"}]}]}
                """);

        Rubric rubric = SpecRubricBuilder.fromSpec(spec);

        assertThat(rubric.rules()).hasSize(1);
        assertThat(rubric.evaluate(mapper.readTree("{\"Other.java\":\"x\"}")).score()).isEqualTo(0.0);
        assertThat(rubric.evaluate(mapper.readTree("{\"a/TaskController.java\":\"x\"}")).score()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("extension spec includes the deterministic domain floor")
    void extensionSpec_includesDomainFloor() throws Exception {
        JsonNode spec = mapper.readTree("""
                {"business_goal":"autofill resume sidebar extension",
                 "runtime_environment":{"deployment_target":"BROWSER_EXTENSION","execution_model":"HYBRID"},
                 "core_features":[{"id":"ui","name":"Sidebar autofill"}]}
                """);

        Rubric rubric = SpecRubricBuilder.fromSpec(spec);

        assertThat(rubric.rules().size()).isGreaterThanOrEqualTo(8);
        // a hollow artifact violates the floor markers (e.g. the slide-out sidebar)
        Rubric.Result hollow = rubric.evaluate(mapper.readTree("{\"popup.js\":\"console.log(1)\"}"));
        assertThat(hollow.violations()).anyMatch(v -> v.contains("ux-sidebar"));
    }

    @Test
    @DisplayName("unknown/empty shape yields a vacuous (1.0) rubric — no false constraints")
    void unknownShape_vacuous() throws Exception {
        JsonNode spec = mapper.readTree("{\"business_goal\":\"x\"}");
        Rubric rubric = SpecRubricBuilder.fromSpec(spec);
        assertThat(rubric.rules()).isEmpty();
        assertThat(rubric.evaluate(mapper.readTree("{\"A.java\":\"x\"}")).score()).isEqualTo(1.0);
    }
}
