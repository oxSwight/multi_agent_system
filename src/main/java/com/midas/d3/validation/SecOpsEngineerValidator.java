package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    private static final Set<String> DOCKER_ARTIFACT_KEYS = Set.of(
            "dockerfile", "docker-compose.yml", "docker-compose.yaml");

    private static final List<String> EXTENSION_ARTIFACT_KEY_MARKERS = List.of(
            "manifest", "extension", "package", "chrome", "store", "packaging", "web-store", "webstore");

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

        String deploymentModel = root.path("deployment_model").asText("").toUpperCase(Locale.ROOT);
        if ("CONTAINERIZED".equals(deploymentModel) || "HYBRID".equals(deploymentModel)) {
            String dockerfileContent = resolveDockerfile(root, releaseArtifacts);
            if (dockerfileContent == null || dockerfileContent.isBlank()) {
                violations.add("deployment_model is " + deploymentModel + " but no Dockerfile was provided "
                        + "(expected a 'Dockerfile' entry in release_artifacts or at the top level).");
            } else if (!dockerfileContent.contains("FROM")) {
                violations.add("The provided Dockerfile does not contain a FROM instruction.");
            }
        }

        if ("HYBRID".equals(deploymentModel) && !hasClientExtensionArtifacts(releaseArtifacts)) {
            violations.add("deployment_model is HYBRID but no client/extension release artifacts were provided "
                    + "(expected packaging steps, store instructions, or manifest summary in release_artifacts).");
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

    private boolean hasClientExtensionArtifacts(JsonNode releaseArtifacts) {
        if (releaseArtifacts == null || !releaseArtifacts.isObject() || releaseArtifacts.isEmpty()) {
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = releaseArtifacts.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            if (DOCKER_ARTIFACT_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String keyLower = key.toLowerCase(Locale.ROOT);
            for (String marker : EXTENSION_ARTIFACT_KEY_MARKERS) {
                if (keyLower.contains(marker)) {
                    return true;
                }
            }
            JsonNode value = entry.getValue();
            if (value != null && value.isTextual()) {
                String valueLower = value.asText().toLowerCase(Locale.ROOT);
                if (valueLower.contains("manifest.json")
                        || valueLower.contains("web store")
                        || valueLower.contains("chrome web store")
                        || valueLower.contains("browser extension")
                        || valueLower.contains("extension.zip")) {
                    return true;
                }
            }
        }
        return false;
    }
}
