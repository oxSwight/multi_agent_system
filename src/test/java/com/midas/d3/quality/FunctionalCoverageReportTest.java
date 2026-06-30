package com.midas.d3.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FunctionalCoverageReport")
class FunctionalCoverageReportTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new JacksonConfig().objectMapper();
    }

    private FunctionalCoverageReport.Entry entry(List<FunctionalCoverageReport.Entry> entries, String id) {
        return entries.stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("extension floor: satisfied criterion cites its evidence file; unmet has none")
    void extensionFloor_satisfiedAndUnmet() throws Exception {
        JsonNode spec = mapper.readTree("""
                {"business_goal":"autofill resume sidebar extension",
                 "runtime_environment":{"deployment_target":"BROWSER_EXTENSION","execution_model":"HYBRID"},
                 "core_features":[{"id":"ui","name":"Sidebar autofill"}]}
                """);
        JsonNode source = mapper.readTree("""
                {"sidebar.html":"<div class='sidebar'></div>","popup.js":"console.log('x')"}
                """);

        List<FunctionalCoverageReport.Entry> entries = FunctionalCoverageReport.evaluate(source, spec);

        FunctionalCoverageReport.Entry sidebar = entry(entries, "ux-sidebar");
        assertThat(sidebar.gated()).isTrue();
        assertThat(sidebar.status()).isEqualTo(FunctionalCoverageReport.Status.SATISFIED);
        assertThat(sidebar.evidenceFile()).isEqualTo("sidebar.html");

        FunctionalCoverageReport.Entry api = entry(entries, "api-matching-call");
        assertThat(api.status()).isEqualTo(FunctionalCoverageReport.Status.UNMET);
        assertThat(api.evidenceFile()).isEmpty();
    }

    @Test
    @DisplayName("model must_exist is gated; satisfied by a matching path")
    void modelMustExist_isGated() throws Exception {
        JsonNode spec = mapper.readTree("""
                {"business_goal":"task api",
                 "runtime_environment":{"deployment_target":"CLOUD_SERVICE","execution_model":"SERVER_SIDE"},
                 "core_features":[{"id":"create-task","name":"Create task",
                   "acceptance_criteria":[{"id":"ctrl","description":"controller exists","must_exist":"TaskController.java"}]}]}
                """);
        JsonNode source = mapper.readTree("{\"a/TaskController.java\":\"class TaskController {}\"}");

        FunctionalCoverageReport.Entry e = entry(FunctionalCoverageReport.evaluate(source, spec), "create-task:ctrl");
        assertThat(e.gated()).isTrue();
        assertThat(e.status()).isEqualTo(FunctionalCoverageReport.Status.SATISFIED);
        assertThat(e.evidenceFile()).isEqualTo("a/TaskController.java");
    }

    @Test
    @DisplayName("model must_contain is advisory (not gated)")
    void modelMustContain_isAdvisory() throws Exception {
        JsonNode spec = mapper.readTree("""
                {"business_goal":"task api",
                 "runtime_environment":{"deployment_target":"CLOUD_SERVICE","execution_model":"SERVER_SIDE"},
                 "core_features":[{"id":"create-task","name":"Create task",
                   "acceptance_criteria":[{"id":"post","description":"has post mapping","must_contain":"@PostMapping"}]}]}
                """);
        JsonNode source = mapper.readTree("{\"TaskController.java\":\"@PostMapping void c(){}\"}");

        FunctionalCoverageReport.Entry e = entry(FunctionalCoverageReport.evaluate(source, spec), "create-task:post");
        assertThat(e.gated()).isFalse();
        assertThat(e.status()).isEqualTo(FunctionalCoverageReport.Status.SATISFIED);
    }
}
