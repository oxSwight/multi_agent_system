package com.midas.d3.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Abstract base for all GoalKeeperValidator implementations.
 *
 * <p>Handles the common defensive layer:
 * <ol>
 *   <li>Null / blank input guard</li>
 *   <li>Jackson parse with structured exception mapping</li>
 *   <li>Non-object root node guard</li>
 *   <li>Delegates to {@link #collectViolations(JsonNode, List)} for domain rules</li>
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractGoalKeeperValidator implements GoalKeeperValidator {

    protected final ObjectMapper objectMapper;

    // ── GoalKeeperValidator impl ─────────────────────────────────────────────

    @Override
    public JsonNode validate(String rawJson) throws ValidationHookException {
        // 1. Null / blank guard
        if (rawJson == null || rawJson.isBlank()) {
            throw new ValidationHookException(agentName(), stage(),
                    "LLM output is null or blank — no JSON to validate.");
        }

        // 2. Parse guard
        JsonNode root;
        try {
            root = objectMapper.readTree(rawJson.strip());
        } catch (JsonProcessingException e) {
            log.warn("[{}][{}] JSON parse failure: {}", agentName(), stage(), e.getOriginalMessage());
            throw new ValidationHookException(agentName(), stage(),
                    "JSON parse error: " + e.getOriginalMessage());
        }

        // 3. Root must be a JSON object
        if (!root.isObject()) {
            throw new ValidationHookException(agentName(), stage(),
                    "Expected JSON object at root, got: " + root.getNodeType());
        }

        // 4. Delegate domain validation
        List<String> violations = new ArrayList<>();
        collectViolations(root, violations);

        if (!violations.isEmpty()) {
            log.warn("[{}][{}] Validation violations: {}", agentName(), stage(), violations);
            throw new ValidationHookException(agentName(), stage(), violations);
        }

        log.debug("[{}][{}] Validation passed.", agentName(), stage());
        return root;
    }

    // ── Template method ──────────────────────────────────────────────────────

    /**
     * Subclasses add domain-specific constraint violations to {@code violations}.
     * The method must NOT throw — it accumulates errors for batch reporting.
     *
     * @param root       guaranteed non-null JSON object node
     * @param violations mutable list to append violation messages to
     */
    protected abstract void collectViolations(JsonNode root, List<String> violations);

    // ── Shared Guard Helpers ─────────────────────────────────────────────────

    protected void requireStringField(JsonNode root, String fieldName, List<String> violations) {
        JsonNode node = root.get(fieldName);
        if (node == null || node.isNull() || node.isMissingNode()) {
            violations.add("Missing required field: '" + fieldName + "'");
        } else if (!node.isTextual() || node.asText().isBlank()) {
            violations.add("Field '" + fieldName + "' must be a non-blank string.");
        }
    }

    protected void requireArrayField(JsonNode root, String fieldName, List<String> violations) {
        requireArrayField(root, fieldName, 1, violations);
    }

    protected void requireArrayField(JsonNode root, String fieldName,
                                     int minItems, List<String> violations) {
        JsonNode node = root.get(fieldName);
        if (node == null || node.isNull() || node.isMissingNode()) {
            violations.add("Missing required array field: '" + fieldName + "'");
        } else if (!node.isArray()) {
            violations.add("Field '" + fieldName + "' must be an array.");
        } else if (node.size() < minItems) {
            violations.add("Array '" + fieldName + "' must have at least "
                    + minItems + " item(s), found " + node.size() + ".");
        }
    }

    protected void requireObjectField(JsonNode root, String fieldName, List<String> violations) {
        JsonNode node = root.get(fieldName);
        if (node == null || node.isNull() || node.isMissingNode()) {
            violations.add("Missing required object field: '" + fieldName + "'");
        } else if (!node.isObject()) {
            violations.add("Field '" + fieldName + "' must be a JSON object.");
        }
    }

    protected void requireBooleanField(JsonNode root, String fieldName, List<String> violations) {
        JsonNode node = root.get(fieldName);
        if (node == null || node.isNull() || node.isMissingNode()) {
            violations.add("Missing required boolean field: '" + fieldName + "'");
        } else if (!node.isBoolean()) {
            violations.add("Field '" + fieldName + "' must be a boolean (true/false).");
        }
    }

    // ── Zero-Placeholder enforcement ─────────────────────────────────────────

    /**
     * Case-insensitive markers that indicate incomplete / stubbed code. The Zero-Placeholder
     * policy rejects any generated source file that contains one of these.
     *
     * <p>Note: bare {@code ...} is deliberately NOT listed because it is valid Java varargs
     * syntax; only an unambiguous ellipsis statement is flagged separately below.
     */
    private static final List<String> PLACEHOLDER_MARKERS = List.of(
            "// todo", "//todo", "/* todo", "todo:",
            "// fixme", "//fixme", "fixme:",
            "unsupportedoperationexception",
            "implement later", "implement me", "your code here",
            "placeholder implementation", "to be implemented", "not implemented");

    /**
     * Scans a generated source file for Zero-Placeholder violations and appends any finding
     * to {@code violations}. Used by the developer and QA validators.
     *
     * @param fileName  the file path (for the violation message)
     * @param content   the raw file contents
     * @param violations mutable list to append to
     */
    protected void rejectPlaceholders(String fileName, String content, List<String> violations) {
        if (content == null || content.isBlank()) return;
        String lower = content.toLowerCase();
        for (String marker : PLACEHOLDER_MARKERS) {
            if (lower.contains(marker)) {
                violations.add("Zero-Placeholder policy violated in [" + fileName
                        + "]: contains forbidden placeholder marker '" + marker.strip() + "'.");
                return; // one finding per file is enough to fail it
            }
        }
        // Flag an ellipsis used as a placeholder statement (e.g. a method body of just "...")
        // without tripping on varargs like "String... args".
        if (content.matches("(?s).*[{;]\\s*\\.\\.\\.\\s*[}]?.*")) {
            violations.add("Zero-Placeholder policy violated in [" + fileName
                    + "]: contains an ellipsis placeholder ('...') in place of real code.");
        }
    }
}
