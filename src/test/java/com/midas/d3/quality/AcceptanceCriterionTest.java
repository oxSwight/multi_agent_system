package com.midas.d3.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AcceptanceCriterion")
class AcceptanceCriterionTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new JacksonConfig().objectMapper();
    }

    @Test
    @DisplayName("fromSpec parses per-feature and UX criteria, namespacing ids by feature")
    void fromSpec_parsesFeatureAndUxCriteria() throws Exception {
        JsonNode spec = mapper.readTree("""
                {
                  "core_features": [
                    {
                      "id": "autofill",
                      "name": "Autofill",
                      "acceptance_criteria": [
                        {"id": "fill-btn", "description": "Fill button", "must_contain": "fill-form", "in_file": "popup.html"},
                        {"id": "manifest", "description": "manifest present", "must_exist": "manifest.json"}
                      ]
                    },
                    "a bare string feature with no criteria"
                  ],
                  "ux_acceptance_criteria": [
                    {"id": "sidebar", "description": "Sidebar", "must_contain": "sidebar"}
                  ]
                }
                """);

        List<AcceptanceCriterion> criteria = AcceptanceCriterion.fromSpec(spec);

        assertThat(criteria).extracting(AcceptanceCriterion::id)
                .containsExactlyInAnyOrder("autofill:fill-btn", "autofill:manifest", "ux:sidebar");
        AcceptanceCriterion fill = criteria.stream().filter(c -> c.id().equals("autofill:fill-btn")).findFirst().orElseThrow();
        assertThat(fill.kind()).isEqualTo(AcceptanceCriterion.Kind.CONTENT);
        assertThat(fill.pathSuffix()).isEqualTo("popup.html");
        AcceptanceCriterion manifest = criteria.stream().filter(c -> c.id().equals("autofill:manifest")).findFirst().orElseThrow();
        assertThat(manifest.kind()).isEqualTo(AcceptanceCriterion.Kind.PATH);
        assertThat(manifest.pattern()).isEqualTo("manifest.json");
    }

    @Test
    @DisplayName("fromSpec tolerates missing/blank/garbage without throwing")
    void fromSpec_isTolerant() throws Exception {
        assertThat(AcceptanceCriterion.fromSpec(null)).isEmpty();
        assertThat(AcceptanceCriterion.fromSpec(mapper.readTree("[]"))).isEmpty();
        assertThat(AcceptanceCriterion.fromSpec(mapper.readTree("""
                {"core_features": ["x"], "ux_acceptance_criteria": [{"id":"no-check","description":"d"}]}
                """))).isEmpty();
    }

    @Test
    @DisplayName("CONTENT criterion matches present marker, reports gap when absent")
    void contentCriterion_evaluates() throws Exception {
        AcceptanceCriterion crit = AcceptanceCriterion.content("c1", "fill button", "", "(?i)fill-form");
        JsonNode present = mapper.readTree("""
                {"popup.html": "<button id=\\"fill-form\\">Go</button>"}
                """);
        JsonNode absent = mapper.readTree("""
                {"popup.html": "<div></div>"}
                """);
        assertThat(crit.toRubricRule().orElseThrow().evaluate(present)).isEmpty();
        assertThat(crit.toRubricRule().orElseThrow().evaluate(absent)).isPresent();
    }

    @Test
    @DisplayName("PATH criterion requires a generated path suffix")
    void pathCriterion_evaluates() throws Exception {
        AcceptanceCriterion crit = AcceptanceCriterion.path("p1", "manifest", "manifest.json");
        JsonNode has = mapper.readTree("""
                {"frontend/manifest.json": "{}"}
                """);
        JsonNode missing = mapper.readTree("""
                {"frontend/app.js": "x"}
                """);
        assertThat(crit.toRubricRule().orElseThrow().evaluate(has)).isEmpty();
        assertThat(crit.toRubricRule().orElseThrow().evaluate(missing)).isPresent();
    }

    @Test
    @DisplayName("Invalid or blank regex is dropped, never throws")
    void invalidRegex_isDropped() {
        assertThat(AcceptanceCriterion.content("c", "d", "", "(unclosed").toRubricRule()).isEmpty();
        assertThat(AcceptanceCriterion.content("c", "d", "", "   ").toRubricRule()).isEmpty();
    }

    @Test
    @DisplayName("violationMessage carries id and description for actionable feedback")
    void violationMessage_isActionable() {
        String msg = AcceptanceCriterion.content("ux-sidebar", "slide-out sidebar container", "", "(?i)sidebar")
                .violationMessage();
        assertThat(msg).contains("ux-sidebar").contains("slide-out sidebar container");
    }
}
