package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates the {@code BUILD_VERIFICATION} stage artifact — the JSON build report produced by
 * {@link com.midas.d3.build.BuildVerificationService}.
 *
 * <p>This validator only enforces that the report is <em>structurally well-formed</em>: a
 * {@code build_status} of exactly {@code SUCCESS} or {@code FAILED}, a tool, and a diagnostics
 * array. It deliberately does NOT treat a {@code FAILED} build as a validation error — both
 * outcomes are valid reports. The build outcome is read separately by the build-remediation
 * guards, which route a {@code FAILED} report back to code generation. (This mirrors how the
 * Controller validator accepts a REJECT verdict as a structurally valid report.)
 */
@Slf4j
@Component
public class BuildVerificationValidator extends AbstractGoalKeeperValidator {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    private static final Set<String> VALID_STATUSES = Set.of(STATUS_SUCCESS, STATUS_FAILED);

    public BuildVerificationValidator(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override public String agentName() { return "BuildVerifier"; }
    @Override public String stage()     { return "BUILD_VERIFICATION"; }

    @Override
    protected void collectViolations(JsonNode root, List<String> violations) {
        JsonNode status = root.get("build_status");
        if (status == null || status.isNull() || !status.isTextual()) {
            violations.add("Missing required string field: 'build_status' (SUCCESS or FAILED).");
        } else if (!VALID_STATUSES.contains(status.asText().strip().toUpperCase(Locale.ROOT))) {
            violations.add("Field 'build_status' must be one of " + VALID_STATUSES
                    + ", got '" + status.asText() + "'.");
        }

        requireStringField(root, "tool", violations);

        JsonNode diagnostics = root.get("diagnostics");
        if (diagnostics == null || diagnostics.isNull() || diagnostics.isMissingNode()) {
            violations.add("Missing required array field: 'diagnostics' (may be empty).");
        } else if (!diagnostics.isArray()) {
            violations.add("Field 'diagnostics' must be an array.");
        }
    }
}
