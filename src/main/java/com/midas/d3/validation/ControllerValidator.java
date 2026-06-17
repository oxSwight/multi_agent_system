package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates Agent 7 (Controller / Product-Owner gate) output against the quality-gate schema.
 *
 * <pre>
 * {
 *   "verdict": "PASS | PASS_WITH_NOTES | REJECT",
 *   "summary": String,
 *   "coverage_matrix": [
 *     {"requested_feature": String, "status": "COVERED | PARTIAL | MISSING", "evidence": String}
 *   ],
 *   "remediation_block": {
 *     "required_changes": [String],
 *     "recommendations": [String]
 *   }
 * }
 * </pre>
 *
 * <p><b>Scope.</b> This validator only enforces the <em>structure</em> of the verdict report — it
 * does NOT decide PASS vs REJECT (that is the LLM's judgement) and it does NOT treat a well-formed
 * REJECT as a validation failure. A REJECT is a perfectly valid report; the state-machine routing
 * ({@code ProductReviewRejectedGuard}) is what turns a REJECT verdict into a terminal ERROR.
 * Consequently, when the verdict is REJECT the {@code remediation_block.required_changes} list must
 * be non-empty so the rejection is actionable.
 */
@Component
public class ControllerValidator extends AbstractGoalKeeperValidator {

    /** Canonical verdict values. Also referenced by the routing guard. */
    public static final String VERDICT_PASS            = "PASS";
    public static final String VERDICT_PASS_WITH_NOTES = "PASS_WITH_NOTES";
    public static final String VERDICT_REJECT          = "REJECT";

    private static final Set<String> VALID_VERDICTS =
            Set.of(VERDICT_PASS, VERDICT_PASS_WITH_NOTES, VERDICT_REJECT);

    private static final Set<String> VALID_STATUSES =
            Set.of("COVERED", "PARTIAL", "MISSING");

    public ControllerValidator(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override public String agentName() { return "ControllerAgent"; }
    @Override public String stage()     { return "PRODUCT_REVIEW"; }

    @Override
    protected void collectViolations(JsonNode root, List<String> violations) {
        String verdict = validateVerdict(root, violations);
        validateCoverageMatrix(root, violations);
        validateRemediationBlock(root, verdict, violations);
    }

    /** @return the upper-cased verdict (possibly invalid/empty); never null. */
    private String validateVerdict(JsonNode root, List<String> violations) {
        JsonNode node = root.get("verdict");
        if (node == null || node.isNull() || node.isMissingNode() || !node.isTextual()
                || node.asText().isBlank()) {
            violations.add("Missing required field: 'verdict' "
                    + "(must be one of PASS, PASS_WITH_NOTES, REJECT).");
            return "";
        }
        String verdict = node.asText().strip().toUpperCase(Locale.ROOT);
        if (!VALID_VERDICTS.contains(verdict)) {
            violations.add("Field 'verdict' must be one of "
                    + VALID_VERDICTS + " but was '" + node.asText().strip() + "'.");
        }
        return verdict;
    }

    private void validateCoverageMatrix(JsonNode root, List<String> violations) {
        JsonNode matrix = root.get("coverage_matrix");
        if (matrix == null || matrix.isNull() || matrix.isMissingNode()) {
            violations.add("Missing required array field: 'coverage_matrix' "
                    + "(must map every requested feature to what was built).");
            return;
        }
        if (!matrix.isArray()) {
            violations.add("Field 'coverage_matrix' must be an array.");
            return;
        }
        if (matrix.isEmpty()) {
            violations.add("Array 'coverage_matrix' must have at least 1 entry "
                    + "(one per requested feature).");
            return;
        }
        for (int i = 0; i < matrix.size(); i++) {
            JsonNode entry = matrix.get(i);
            if (!entry.isObject()) {
                violations.add("coverage_matrix[" + i + "] must be an object.");
                continue;
            }
            JsonNode feature = entry.get("requested_feature");
            if (feature == null || !feature.isTextual() || feature.asText().isBlank()) {
                violations.add("coverage_matrix[" + i + "].requested_feature is missing or blank.");
            }
            JsonNode status = entry.get("status");
            if (status == null || !status.isTextual() || status.asText().isBlank()) {
                violations.add("coverage_matrix[" + i + "].status is missing or blank.");
            } else if (!VALID_STATUSES.contains(status.asText().strip().toUpperCase(Locale.ROOT))) {
                violations.add("coverage_matrix[" + i + "].status must be one of "
                        + VALID_STATUSES + " but was '" + status.asText().strip() + "'.");
            }
        }
    }

    private void validateRemediationBlock(JsonNode root, String verdict, List<String> violations) {
        JsonNode block = root.get("remediation_block");
        if (block == null || block.isNull() || block.isMissingNode()) {
            violations.add("Missing required object field: 'remediation_block' "
                    + "(use empty arrays rather than omitting it).");
            return;
        }
        if (!block.isObject()) {
            violations.add("Field 'remediation_block' must be a JSON object.");
            return;
        }

        JsonNode requiredChanges = block.get("required_changes");
        if (requiredChanges != null && !requiredChanges.isNull() && !requiredChanges.isArray()) {
            violations.add("remediation_block.required_changes must be an array when present.");
        }

        // A REJECT verdict must carry actionable remediation — otherwise the rejection is useless.
        if (VERDICT_REJECT.equals(verdict)) {
            boolean hasActionableChanges = requiredChanges != null
                    && requiredChanges.isArray() && !requiredChanges.isEmpty();
            if (!hasActionableChanges) {
                violations.add("verdict is REJECT but remediation_block.required_changes is empty — "
                        + "a rejection must list the concrete changes required to pass.");
            }
        }
    }
}
