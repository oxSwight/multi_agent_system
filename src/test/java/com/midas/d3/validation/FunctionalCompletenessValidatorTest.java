package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The functional-fidelity gate (F2). Proves the core promise of the layer: a structurally-valid but
 * <em>hollow</em> resume-autofill extension (the empty popup with a dead "Fill" button) is REJECTed
 * with actionable, feature-level feedback, while a genuinely rich implementation passes — and that an
 * unrecognized product shape is never falsely constrained.
 */
@DisplayName("FunctionalCompletenessValidator (F2)")
class FunctionalCompletenessValidatorTest {

    private ObjectMapper mapper;

    private static final String AUTOFILL_SPEC = """
            {
              "business_goal": "A browser extension that autofills job application forms from a stored resume",
              "runtime_environment": {"deployment_target": "BROWSER_EXTENSION", "execution_model": "HYBRID"},
              "core_features": [{"id": "sidebar-autofill", "name": "Sidebar autofill with profile picker"}]
            }
            """;

    @BeforeEach
    void setUp() {
        mapper = new JacksonConfig().objectMapper();
    }

    @Test
    @DisplayName("Hollow autofill extension reports the missing-capability functional gaps")
    void hollowExtension_reportsFunctionalGaps() throws Exception {
        JsonNode spec = mapper.readTree(AUTOFILL_SPEC);
        JsonNode hollow = mapper.readTree("""
                {
                  "popup.html": "<select id='profiles'></select><button id='fill'>Fill</button>",
                  "popup.js": "document.getElementById('fill').onclick = () => {};"
                }
                """);

        List<String> violations = new ArrayList<>();
        FunctionalCompletenessValidator.validate(hollow, spec, violations);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.contains("ux-sidebar"));
        assertThat(violations).anyMatch(v -> v.contains("api-matching-call"));
        assertThat(violations).anyMatch(v -> v.contains("cs-semantic-scan"));
        // The wired dropdown and click handler that DO exist are not falsely flagged.
        assertThat(violations).noneMatch(v -> v.contains("ux-profile-dropdown"));
        assertThat(violations).noneMatch(v -> v.contains("ext-event-handler"));
    }

    @Test
    @DisplayName("Rich autofill extension satisfies every floor criterion")
    void richExtension_passes() throws Exception {
        JsonNode spec = mapper.readTree(AUTOFILL_SPEC);
        JsonNode rich = mapper.readTree("""
                {
                  "sidebar.css": ".sidebar{position:fixed} .capsule{} .confident{color:green} .uncertain{color:yellow}",
                  "sidebar.html": "<div class='sidebar'><div class='capsule'></div><select class='profile-select'></select><div class='field' contenteditable='true'></div><button class='fill-form-btn'>Fill form</button></div>",
                  "content_script.js": "const fields=document.querySelectorAll('input,textarea,select'); fields.forEach(f=>f.getAttribute('aria-label')||f.placeholder); fetch('/api/match');",
                  "popup.js": "document.addEventListener('click', () => {});"
                }
                """);

        List<String> violations = new ArrayList<>();
        FunctionalCompletenessValidator.validate(rich, spec, violations);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("Unrecognized product shape with no criteria is a no-op (no false constraints)")
    void unknownShape_isNoOp() throws Exception {
        JsonNode spec = mapper.readTree("""
                {"business_goal": "REST API", "runtime_environment": {"deployment_target": "CLOUD_SERVICE"},
                 "core_features": [{"id": "crud", "name": "CRUD"}]}
                """);
        JsonNode source = mapper.readTree("""
                {"App.java": "public class App {}"}
                """);

        List<String> violations = new ArrayList<>();
        FunctionalCompletenessValidator.validate(source, spec, violations);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("A brittle model-authored content regex is advisory — it never hard-rejects correct code")
    void brittleModelContentCriterion_isAdvisory() throws Exception {
        // A non-extension product so the deterministic floor is empty: only model criteria are in play.
        JsonNode spec = mapper.readTree("""
                {
                  "business_goal": "A REST API for tasks",
                  "runtime_environment": {"deployment_target": "CLOUD_SERVICE", "execution_model": "SERVER_SIDE"},
                  "core_features": [{
                    "id": "create-task", "name": "Create task",
                    "acceptance_criteria": [
                      {"id": "c1", "description": "create endpoint",
                       "must_contain": "@PostMapping\\\\(\\"/tasks\\"\\\\).*public Task createTaskExactlyNamed.*return repo.save"}
                    ]
                  }]
                }
                """);
        JsonNode source = mapper.readTree("""
                {"TaskController.java": "@PostMapping(\\"/tasks\\")\\npublic Task add(@RequestBody Task t){ return service.create(t); }"}
                """);

        List<String> violations = new ArrayList<>();
        FunctionalCompletenessValidator.validate(source, spec, violations);

        // The over-specified, multi-line content regex does NOT match the (correct) source, yet it is
        // advisory, so it produces no hard violation.
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("A model-authored must_exist (file presence) criterion IS hard-gated")
    void modelMustExistCriterion_isHardGated() throws Exception {
        JsonNode spec = mapper.readTree("""
                {
                  "business_goal": "A REST API for tasks",
                  "runtime_environment": {"deployment_target": "CLOUD_SERVICE", "execution_model": "SERVER_SIDE"},
                  "core_features": [{
                    "id": "create-task", "name": "Create task",
                    "acceptance_criteria": [
                      {"id": "ctrl", "description": "controller exists", "must_exist": "TaskController.java"}
                    ]
                  }]
                }
                """);
        JsonNode missing = mapper.readTree("""
                {"TaskService.java": "class TaskService {}"}
                """);

        List<String> violations = new ArrayList<>();
        FunctionalCompletenessValidator.validate(missing, spec, violations);

        assertThat(violations).anyMatch(v -> v.contains("create-task:ctrl"));
    }

    @Test
    @DisplayName("Through its real host (validateWithTechnicalSpec) a hollow extension is REJECTed with a functional gap")
    void throughHost_hollowExtension_isRejected() throws Exception {
        ImplementationEngineerValidator validator =
                new ImplementationEngineerValidator(mapper, new FeatureManifestValidator());
        JsonNode spec = mapper.readTree(AUTOFILL_SPEC);
        JsonNode architecture = mapper.readTree("""
                {"file_layout": ["popup.html", "popup.js"], "api_contracts": []}
                """);
        String envelope = """
                {
                  "source_files": {
                    "popup.html": "<button id='fill'>Fill</button><script src='popup.js'></script>",
                    "popup.js": "document.getElementById('fill').onclick = () => {};"
                  },
                  "feature_manifest": [
                    {"feature_id": "sidebar-autofill", "feature_name": "Sidebar autofill",
                     "files": ["popup.js"], "entry_points": ["popup"]}
                  ]
                }
                """;

        assertThatThrownBy(() -> validator.validateWithTechnicalSpec(envelope, spec, architecture))
                .isInstanceOf(ValidationHookException.class)
                .satisfies(thrown -> assertThat(((ValidationHookException) thrown).getViolations())
                        .anyMatch(v -> v.startsWith("Functional gap")));
    }
}
