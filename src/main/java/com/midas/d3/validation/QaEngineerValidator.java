package com.midas.d3.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Validates Agent 5 (QA Engineer) output.
 *
 * <p>Per-file generation: LLM returns a markdown code block; the pipeline supplies the test path.
 * Assembled map shape: {@code { "path/to/Test.ext": "test source code" }}.
 */
@Component
public class QaEngineerValidator extends AbstractGoalKeeperValidator {

    public QaEngineerValidator(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override public String agentName() { return "QaEngineer"; }
    @Override public String stage()     { return "TEST_GENERATION"; }

    public JsonNode validatePatchDelta(String rawJson) throws ValidationHookException {
        return validate(rawJson);
    }

    /**
     * Extracts and validates raw test source from a per-file LLM response.
     * The pipeline supplies {@code expectedPath}; the LLM must return only a markdown code block.
     */
    public String validateSingleFileOutput(String rawOutput, String expectedPath)
            throws ValidationHookException {
        if (rawOutput == null || rawOutput.isBlank()) {
            throw new ValidationHookException(agentName(), stage(),
                    "LLM output is null or blank — expected a markdown code block.");
        }

        String trimmed = rawOutput.strip();
        rejectJsonEnvelope(trimmed);

        String content = MarkdownCodeBlockExtractor.extract(trimmed);
        if (content == null || content.isBlank()) {
            throw new ValidationHookException(agentName(), stage(),
                    "LLM output must be a single markdown code block (```language ... ```) "
                            + "containing the complete test source for [" + expectedPath + "].");
        }

        List<String> violations = new java.util.ArrayList<>();
        validateTestFileName(expectedPath, violations);
        rejectPlaceholders(expectedPath, content, violations);
        if (!violations.isEmpty()) {
            throw new ValidationHookException(agentName(), stage(), violations);
        }
        return content;
    }

    private void rejectJsonEnvelope(String trimmed) {
        if (!trimmed.startsWith("{")) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(trimmed);
            if (root.isObject()) {
                throw new ValidationHookException(agentName(), stage(),
                        "JSON envelope forbidden — output raw test source in a single markdown code block only.");
            }
        } catch (JsonProcessingException ignored) {
            // Not JSON — no envelope to reject.
        }
    }

    private static void validateTestFileName(String fileName, List<String> violations) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.contains("test") && !lower.contains("spec")) {
            violations.add("Test file [" + fileName + "] name should identify it as a test "
                    + "(e.g. 'Test'/'Spec' for Java, '.test.'/'.spec.' for JS/TS).");
        }
    }

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
            validateTestFileName(fileName, violations);
            rejectPlaceholders(fileName, value.asText(), violations);
        }
    }
}
