package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Validates Agent 6 (SecOps Engineer) output against the v2, runtime-aware schema.
 *
 * <pre>
 * {
 *   "security_audit_report": [String],
 *   "deployment_model": "BROWSER_EXTENSION_PACKAGE | STATIC_DEPLOY | CLI_DISTRIBUTION | CONTAINERIZED",
 *   "release_artifacts": { "<name>": "<contents/instructions>" }
 * }
 * </pre>
 *
 * <p>Containers are no longer mandatory. A {@code Dockerfile} (with a {@code FROM} instruction)
 * is required <b>only</b> when {@code deployment_model} is {@code CONTAINERIZED}; for a browser
 * extension, a Dockerfile would be wrong. For backward compatibility the legacy top-level
 * {@code Dockerfile} / {@code docker-compose.yml} keys are still accepted as release artifacts.
 */
@Component
public class SecOpsEngineerValidator extends AbstractGoalKeeperValidator {

    public SecOpsEngineerValidator(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override public String agentName() { return "SecOpsEngineer"; }
    @Override public String stage()     { return "SECOPS_AUDIT"; }

    @Override
    protected void collectViolations(JsonNode root, List<String> violations) {
        requireArrayField(root, "security_audit_report", 0, violations);

        JsonNode releaseArtifacts = root.get("release_artifacts");
        boolean hasReleaseArtifacts = releaseArtifacts != null
                && releaseArtifacts.isObject() && !releaseArtifacts.isEmpty();

        // Legacy support: top-level Dockerfile/docker-compose.yml count as release artifacts.
        JsonNode legacyDockerfile = root.get("Dockerfile");
        boolean hasLegacyDockerfile = legacyDockerfile != null
                && legacyDockerfile.isTextual() && !legacyDockerfile.asText().isBlank();

        if (releaseArtifacts != null && !releaseArtifacts.isNull() && !releaseArtifacts.isObject()) {
            violations.add("Field 'release_artifacts' must be a JSON object when present.");
        }

        if (!hasReleaseArtifacts && !hasLegacyDockerfile) {
            violations.add("At least one release artifact is required: provide a non-empty "
                    + "'release_artifacts' object (or a legacy 'Dockerfile').");
        }

        // Docker is required ONLY for containerized deployments.
        String deploymentModel = root.path("deployment_model").asText("").toUpperCase();
        if ("CONTAINERIZED".equals(deploymentModel)) {
            String dockerfileContent = resolveDockerfile(root, releaseArtifacts);
            if (dockerfileContent == null || dockerfileContent.isBlank()) {
                violations.add("deployment_model is CONTAINERIZED but no Dockerfile was provided "
                        + "(expected a 'Dockerfile' entry in release_artifacts or at the top level).");
            } else if (!dockerfileContent.contains("FROM")) {
                violations.add("The provided Dockerfile does not contain a FROM instruction.");
            }
        }
    }

    /** Looks for Dockerfile content in release_artifacts first, then the legacy top-level key. */
    private String resolveDockerfile(JsonNode root, JsonNode releaseArtifacts) {
        if (releaseArtifacts != null && releaseArtifacts.isObject()) {
            JsonNode inArtifacts = releaseArtifacts.get("Dockerfile");
            if (inArtifacts != null && inArtifacts.isTextual()) {
                return inArtifacts.asText();
            }
        }
        JsonNode legacy = root.get("Dockerfile");
        return (legacy != null && legacy.isTextual()) ? legacy.asText() : null;
    }
}
