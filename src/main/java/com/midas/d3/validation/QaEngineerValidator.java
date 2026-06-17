package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Validates Agent 5 (QA Engineer) output.
 *
 * <p>Same shape as the Implementation Engineer: {@code { "path/to/Test.ext": "test source code" }}.
 * File-name conventions are <b>stack-agnostic and case-insensitive</b> so both Java
 * ({@code *Test.java}, {@code *Spec.java}) and JS/TS ({@code *.test.ts}, {@code *.spec.js},
 * {@code __tests__/...}) conventions are accepted. Zero-Placeholder is enforced.
 */
@Component
public class QaEngineerValidator extends AbstractGoalKeeperValidator {

    public QaEngineerValidator(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override public String agentName() { return "QaEngineer"; }
    @Override public String stage()     { return "TEST_GENERATION"; }

    @Override
    protected void collectViolations(JsonNode root, List<String> violations) {
        if (root.isEmpty()) {
            violations.add("Generated test map must not be empty.");
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String fileName = entry.getKey();
            JsonNode value  = entry.getValue();
            if (fileName.isBlank()) {
                violations.add("Test file map contains a blank key (filename).");
                continue;
            }
            if (!value.isTextual() || value.asText().isBlank()) {
                violations.add("Test file [" + fileName + "] has blank or non-string content.");
                continue;
            }
            // Case-insensitive: matches Test.java, Spec.java, *.test.ts, *.spec.js, __tests__/...
            String lower = fileName.toLowerCase();
            if (!lower.contains("test") && !lower.contains("spec")) {
                violations.add("Test file [" + fileName + "] name should identify it as a test "
                        + "(e.g. 'Test'/'Spec' for Java, '.test.'/'.spec.' for JS/TS).");
            }
            rejectPlaceholders(fileName, value.asText(), violations);
        }
    }
}
