package com.midas.d3.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PathConformanceValidatorTest {

    private ObjectMapper objectMapper;
    private List<String> violations;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
        violations = new ArrayList<>();
    }

    private ObjectNode architectureWithLayout(String... layout) {
        ObjectNode architecture = objectMapper.createObjectNode();
        var arr = architecture.putArray("file_layout");
        for (String p : layout) {
            arr.add(p);
        }
        return architecture;
    }

    @Test
    void manifestNestedUnderExtraWrapper_isReported() {
        ObjectNode architecture = architectureWithLayout(
                "frontend/manifest.json", "frontend/background.js");
        ObjectNode sourceFiles = objectMapper.createObjectNode();
        // BLOCKER reproduction: manifest emitted under an extra src/ wrapper.
        sourceFiles.put("src/frontend/manifest.json", "{}");
        sourceFiles.put("src/frontend/background.js", "// worker");

        PathConformanceValidator.validate(sourceFiles, architecture, violations);

        assertThat(violations).anyMatch(v ->
                v.contains("src/frontend/manifest.json") && v.contains("frontend/manifest.json"));
        assertThat(violations).anyMatch(v -> v.contains("wrapper"));
    }

    @Test
    void manifestAtDeclaredRoot_passes() {
        ObjectNode architecture = architectureWithLayout(
                "frontend/manifest.json", "frontend/background.js");
        ObjectNode sourceFiles = objectMapper.createObjectNode();
        sourceFiles.put("frontend/manifest.json", "{}");
        sourceFiles.put("frontend/background.js", "// worker");

        PathConformanceValidator.validate(sourceFiles, architecture, violations);

        assertThat(violations).isEmpty();
    }

    @Test
    void extraScaffoldingBeyondLayout_isTolerated() {
        ObjectNode architecture = architectureWithLayout("frontend/popup.js");
        ObjectNode sourceFiles = objectMapper.createObjectNode();
        sourceFiles.put("frontend/popup.js", "// ok");
        // package.json is legitimately added beyond the layout and is NOT a wrapper of any entry.
        sourceFiles.put("frontend/package.json", "{}");

        PathConformanceValidator.validate(sourceFiles, architecture, violations);

        assertThat(violations).isEmpty();
    }

    @Test
    void noFileLayout_isNoOp() {
        ObjectNode architecture = objectMapper.createObjectNode();
        ObjectNode sourceFiles = objectMapper.createObjectNode();
        sourceFiles.put("src/whatever.js", "// x");

        PathConformanceValidator.validate(sourceFiles, architecture, violations);

        assertThat(violations).isEmpty();
    }
}
