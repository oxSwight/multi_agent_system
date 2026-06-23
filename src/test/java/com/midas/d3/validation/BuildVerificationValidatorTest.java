package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BuildVerificationValidator")
class BuildVerificationValidatorTest {

    private final BuildVerificationValidator validator =
            new BuildVerificationValidator(new ObjectMapper());

    @Test
    @DisplayName("a SUCCESS report is structurally valid")
    void successReportValid() throws Exception {
        JsonNode node = validator.validate("""
                {"build_status":"SUCCESS","tool":"MAVEN","diagnostics":[]}
                """);
        assertThat(node.get("build_status").asText()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("a FAILED report is ALSO structurally valid (outcome is read by the guards, not the validator)")
    void failedReportAlsoValid() throws Exception {
        JsonNode node = validator.validate("""
                {"build_status":"FAILED","tool":"MAVEN","diagnostics":[
                  {"file":"App.java","line":3,"severity":"ERROR","message":"cannot find symbol"}]}
                """);
        assertThat(node.get("build_status").asText()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("an unknown build_status is rejected")
    void unknownStatusRejected() {
        assertThatThrownBy(() -> validator.validate("""
                {"build_status":"MAYBE","tool":"MAVEN","diagnostics":[]}
                """)).isInstanceOf(ValidationHookException.class);
    }

    @Test
    @DisplayName("missing diagnostics array is rejected")
    void missingDiagnosticsRejected() {
        assertThatThrownBy(() -> validator.validate("""
                {"build_status":"SUCCESS","tool":"MAVEN"}
                """)).isInstanceOf(ValidationHookException.class);
    }

    @Test
    @DisplayName("missing tool is rejected")
    void missingToolRejected() {
        assertThatThrownBy(() -> validator.validate("""
                {"build_status":"SUCCESS","diagnostics":[]}
                """)).isInstanceOf(ValidationHookException.class);
    }
}
