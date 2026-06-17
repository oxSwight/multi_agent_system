package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Validates Agent 4 (Lead Implementation Engineer) output.
 *
 * <p>The schema is a flat, stack-agnostic JSON object: {@code { "path/to/File.ext": "source code" }}.
 * Any file extension is accepted (.js/.ts/manifest.json for client tools, .java for backends).
 *
 * <p>Enforces the <b>Zero-Placeholder policy</b>: every file must contain complete, runnable
 * code — {@code // TODO}, {@code UnsupportedOperationException}, ellipsis stubs, etc. are rejected.
 */
@Component
public class ImplementationEngineerValidator extends AbstractGoalKeeperValidator {

    public ImplementationEngineerValidator(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override public String agentName() { return "ImplementationEngineer"; }
    @Override public String stage()     { return "CODE_GENERATION"; }

    @Override
    protected void collectViolations(JsonNode root, List<String> violations) {
        if (root.isEmpty()) {
            violations.add("Generated source code map must not be empty.");
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String fileName = entry.getKey();
            JsonNode value  = entry.getValue();
            if (fileName.isBlank()) {
                violations.add("Source file map contains a blank key (filename).");
                continue;
            }
            if (!value.isTextual() || value.asText().isBlank()) {
                violations.add("Source file [" + fileName + "] has blank or non-string content.");
                continue;
            }
            rejectPlaceholders(fileName, value.asText(), violations);
        }
    }
}
