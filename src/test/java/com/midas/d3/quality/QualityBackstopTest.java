package com.midas.d3.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("QualityBackstop")
class QualityBackstopTest {

    private ObjectMapper mapper;

    private static final String SPRING_CRUD_SPEC = """
            {"business_goal":"A Spring Boot REST API with full CRUD: create, read, update, delete tasks",
             "runtime_environment":{"deployment_target":"CLOUD_SERVICE","execution_model":"SERVER_SIDE"},
             "core_features":[{"id":"task-crud","name":"CRUD tasks"}]}
            """;

    @BeforeEach
    void setUp() {
        mapper = new JacksonConfig().objectMapper();
    }

    private JsonNode fullCrudSource() throws Exception {
        return mapper.readTree("""
                {"src/main/java/app/TaskController.java":
                   "@RestController class TaskController { @PostMapping a(){} @GetMapping b(){} @PutMapping c(){} @DeleteMapping d(){} }",
                 "src/main/java/app/Task.java":"@Entity class Task {}",
                 "src/main/java/app/TaskRepository.java":"interface TaskRepository extends JpaRepository<Task,Long> {}"}
                """);
    }

    private JsonNode tests() throws Exception {
        return mapper.readTree("{}");
    }

    private JsonNode build(String status) throws Exception {
        return mapper.readTree("{\"build_status\":\"" + status + "\"}");
    }

    @Test
    @DisplayName("qualifies when the gated rubric is fully satisfied and the build did not fail")
    void qualifies_fullyStatisfiedAndBuildOk() throws Exception {
        JsonNode spec = mapper.readTree(SPRING_CRUD_SPEC);
        assertThat(QualityBackstop.qualifies(spec, fullCrudSource(), tests(), build("SUCCESS"), mapper)).isTrue();
    }

    @Test
    @DisplayName("does NOT qualify when there is no gated rubric (vacuous score must never backstop)")
    void deniesWhenRubricEmpty() throws Exception {
        // Plain CLOUD_SERVICE with no Java/Spring/CRUD signal → SpecRubricBuilder yields no rules.
        JsonNode spec = mapper.readTree(
                "{\"business_goal\":\"a plain service\",\"runtime_environment\":{\"deployment_target\":\"CLOUD_SERVICE\"}}");
        assertThat(QualityBackstop.qualifies(spec, fullCrudSource(), tests(), build("SUCCESS"), mapper)).isFalse();
    }

    @Test
    @DisplayName("does NOT qualify when any gated criterion is unsatisfied")
    void deniesWhenCriterionMissing() throws Exception {
        JsonNode spec = mapper.readTree(SPRING_CRUD_SPEC);
        JsonNode missingDelete = mapper.readTree("""
                {"src/main/java/app/TaskController.java":
                   "@RestController class TaskController { @PostMapping a(){} @GetMapping b(){} @PutMapping c(){} }",
                 "src/main/java/app/Task.java":"@Entity class Task {}",
                 "src/main/java/app/TaskRepository.java":"interface TaskRepository extends JpaRepository<Task,Long> {}"}
                """);
        assertThat(QualityBackstop.qualifies(spec, missingDelete, tests(), build("SUCCESS"), mapper)).isFalse();
    }

    @Test
    @DisplayName("does NOT qualify when the build failed")
    void deniesWhenBuildFailed() throws Exception {
        JsonNode spec = mapper.readTree(SPRING_CRUD_SPEC);
        assertThat(QualityBackstop.qualifies(spec, fullCrudSource(), tests(), build("FAILED"), mapper)).isFalse();
    }
}
