package com.midas.d3.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DomainCriteriaFloor")
class DomainCriteriaFloorTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new JacksonConfig().objectMapper();
    }

    @Test
    @DisplayName("Resume-autofill browser extension gets the rich sidebar floor")
    void autofillExtension_getsRichFloor() throws Exception {
        JsonNode spec = mapper.readTree("""
                {
                  "business_goal": "A browser extension that autofills job application forms from a resume",
                  "runtime_environment": {"deployment_target": "BROWSER_EXTENSION", "execution_model": "HYBRID"},
                  "core_features": [{"id": "sidebar-ui", "name": "Slide-out sidebar with profile picker"}]
                }
                """);

        List<String> ids = DomainCriteriaFloor.forSpec(spec).stream().map(AcceptanceCriterion::id).toList();

        assertThat(ids).contains(
                "ext-event-handler", "ux-sidebar", "ux-capsule", "ux-profile-dropdown",
                "ux-fill-button", "ux-highlight-confident", "ux-highlight-uncertain", "ux-inline-edit",
                "cs-semantic-scan", "cs-semantic-signals", "api-matching-call");
    }

    @Test
    @DisplayName("HYBRID compound deployment_target ('BROWSER_EXTENSION + CLOUD_SERVICE') still triggers the floor")
    void compoundDeploymentTarget_triggersFloor() throws Exception {
        JsonNode spec = mapper.readTree("""
                {
                  "business_goal": "Autofill job application forms from a resume via a sidebar extension",
                  "runtime_environment": {"deployment_target": "BROWSER_EXTENSION + CLOUD_SERVICE", "execution_model": "HYBRID"},
                  "core_features": [{"id": "ui", "name": "Sidebar autofill"}]
                }
                """);

        List<String> ids = DomainCriteriaFloor.forSpec(spec).stream().map(AcceptanceCriterion::id).toList();

        assertThat(ids).contains("ux-sidebar", "ux-fill-button", "api-matching-call");
    }

    @Test
    @DisplayName("Plain (non-autofill) browser extension gets only the baseline floor, not the sidebar floor")
    void plainExtension_getsBaselineOnly() throws Exception {
        JsonNode spec = mapper.readTree("""
                {
                  "business_goal": "A browser extension that changes the page background color",
                  "runtime_environment": {"deployment_target": "BROWSER_EXTENSION", "execution_model": "CLIENT_SIDE"},
                  "core_features": [{"id": "recolor", "name": "Recolor the page"}]
                }
                """);

        List<String> ids = DomainCriteriaFloor.forSpec(spec).stream().map(AcceptanceCriterion::id).toList();

        assertThat(ids).containsExactly("ext-event-handler");
        assertThat(ids).doesNotContain("ux-sidebar", "ux-capsule");
    }

    @Test
    @DisplayName("Non-extension product gets no floor (no false constraints)")
    void nonExtension_getsNoFloor() throws Exception {
        JsonNode spec = mapper.readTree("""
                {
                  "business_goal": "A REST API for managing tasks",
                  "runtime_environment": {"deployment_target": "CLOUD_SERVICE", "execution_model": "SERVER_SIDE"},
                  "core_features": [{"id": "crud", "name": "CRUD tasks"}]
                }
                """);
        assertThat(DomainCriteriaFloor.forSpec(spec)).isEmpty();
        assertThat(DomainCriteriaFloor.forSpec(null)).isEmpty();
        assertThat(DomainCriteriaFloor.forSpec(mapper.readTree("{}"))).isEmpty();
    }

    @Test
    @DisplayName("enrich folds floor criteria into the spec's ux_acceptance_criteria for the generation prompt")
    void enrich_injectsFloorIntoSpec() throws Exception {
        JsonNode spec = mapper.readTree("""
                {
                  "business_goal": "Autofill job forms from a resume via a sidebar extension",
                  "runtime_environment": {"deployment_target": "BROWSER_EXTENSION", "execution_model": "HYBRID"},
                  "core_features": [{"id": "ui", "name": "Sidebar"}]
                }
                """);

        JsonNode enriched = DomainCriteriaFloor.enrich(spec, mapper);

        assertThat(enriched.has("ux_acceptance_criteria")).isTrue();
        List<String> ids = enriched.get("ux_acceptance_criteria").findValues("id")
                .stream().map(JsonNode::asText).toList();
        assertThat(ids).contains("ux-sidebar", "ux-fill-button", "api-matching-call");
    }

    @Test
    @DisplayName("enrich keeps a model-authored criterion of the same id (no duplicate)")
    void enrich_doesNotDuplicateModelCriterion() throws Exception {
        JsonNode spec = mapper.readTree("""
                {
                  "business_goal": "Autofill resume sidebar extension",
                  "runtime_environment": {"deployment_target": "BROWSER_EXTENSION", "execution_model": "HYBRID"},
                  "core_features": [{"id": "ui", "name": "Sidebar"}],
                  "ux_acceptance_criteria": [
                    {"id": "ux-sidebar", "description": "my own", "must_contain": "myCustomSidebar"}
                  ]
                }
                """);

        JsonNode enriched = DomainCriteriaFloor.enrich(spec, mapper);

        long sidebarCount = enriched.get("ux_acceptance_criteria").findValues("id").stream()
                .map(JsonNode::asText).filter("ux-sidebar"::equals).count();
        assertThat(sidebarCount).isEqualTo(1);
    }

    @Test
    @DisplayName("enrich is a no-op for shapes with no floor")
    void enrich_noOpWithoutFloor() throws Exception {
        JsonNode spec = mapper.readTree("""
                {"business_goal": "API", "runtime_environment": {"deployment_target": "CLOUD_SERVICE"}}
                """);
        assertThat(DomainCriteriaFloor.enrich(spec, mapper)).isEqualTo(spec);
    }
}
