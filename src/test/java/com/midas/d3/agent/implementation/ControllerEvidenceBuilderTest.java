package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ControllerEvidenceBuilder")
class ControllerEvidenceBuilderTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new JacksonConfig().objectMapper();
    }

    private List<String> coverageIds(JsonNode evidence) {
        List<String> ids = new ArrayList<>();
        evidence.path("functional_coverage").forEach(n -> ids.add(n.path("id").asText()));
        return ids;
    }

    @Test
    @DisplayName("emits capability-level functional_coverage with evidence file — and no per-file code digest")
    void build_capabilityCoverageOnly() throws Exception {
        JsonNode spec = mapper.readTree("""
                {"business_goal":"task api",
                 "runtime_environment":{"deployment_target":"CLOUD_SERVICE","execution_model":"SERVER_SIDE"},
                 "core_features":[{"id":"task","name":"Task",
                   "acceptance_criteria":[{"id":"ctrl","description":"controller exists","must_exist":"TaskController.java"}]}]}
                """);
        JsonNode source = mapper.readTree("{\"a/TaskController.java\":\"public class TaskController {}\"}");

        JsonNode evidence = ControllerEvidenceBuilder.build(source, spec, mapper);

        assertThat(evidence.has("functional_coverage")).isTrue();
        // capability altitude only — the per-file symbol digest that caused false-rejects is gone
        assertThat(evidence.has("file_api")).isFalse();
        assertThat(evidence.has("feature_files")).isFalse();

        JsonNode ctrl = evidence.path("functional_coverage").get(0);
        assertThat(ctrl.path("id").asText()).isEqualTo("task:ctrl");
        assertThat(ctrl.path("status").asText()).isEqualTo("SATISFIED");
        assertThat(ctrl.path("evidence_file").asText()).isEqualTo("a/TaskController.java");
    }

    @Test
    @DisplayName("functional_coverage keeps gated + SATISFIED advisory, suppresses advisory UNMET")
    void build_suppressesAdvisoryUnmet() throws Exception {
        JsonNode spec = mapper.readTree("""
                {"business_goal":"task api",
                 "runtime_environment":{"deployment_target":"CLOUD_SERVICE","execution_model":"SERVER_SIDE"},
                 "core_features":[{"id":"task","name":"Task",
                   "acceptance_criteria":[
                     {"id":"ctrl","description":"controller exists","must_exist":"TaskController.java"},
                     {"id":"present","description":"has create","must_contain":"@PostMapping"},
                     {"id":"absent","description":"has websocket","must_contain":"@MessageMapping"}
                   ]}]}
                """);
        JsonNode source = mapper.readTree("{\"a/TaskController.java\":\"@PostMapping void c(){}\"}");

        List<String> ids = coverageIds(ControllerEvidenceBuilder.build(source, spec, mapper));

        assertThat(ids).contains("task:ctrl");         // gated must_exist (SATISFIED) → shown
        assertThat(ids).contains("task:present");      // advisory SATISFIED → shown
        assertThat(ids).doesNotContain("task:absent"); // advisory UNMET → suppressed
    }
}
