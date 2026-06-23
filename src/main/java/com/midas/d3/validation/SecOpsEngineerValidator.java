package com.midas.d3.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates Agent 6 (SecOps Engineer) output.
 *
 * <p>Primary path: LLM returns markdown — audit findings as bullet lines and release artifacts
 * as fenced code blocks. The validator assembles the internal JSON artifact shape.
 *
 * <p>Legacy JSON envelopes are still accepted for backward compatibility.
 */
@Slf4j
@Component
public class SecOpsEngineerValidator extends AbstractGoalKeeperValidator {

    private static final Set<String> DOCKER_ARTIFACT_KEYS = Set.of(
            "dockerfile", "docker-compose.yml", "docker-compose.yaml");

    private static final List<String> EXTENSION_ARTIFACT_KEY_MARKERS = List.of(
            "manifest", "extension", "package", "chrome", "store", "packaging", "web-store", "webstore");

    private static final Pattern DEPLOYMENT_MODEL_LINE = Pattern.compile(
            "(?im)^\\s*DEPLOYMENT_MODEL\\s*:\\s*(\\S+)\\s*$");

    private static final Pattern AUDIT_BULLET = Pattern.compile(
            "^\\s*[-*]\\s+(.+)$");

    public SecOpsEngineerValidator(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override public String agentName() { return "SecOpsEngineer"; }
    @Override public String stage()     { return "SECOPS_AUDIT"; }

    @Override
    public JsonNode validate(String rawOutput) throws ValidationHookException {
        if (rawOutput == null || rawOutput.isBlank()) {
            throw new ValidationHookException(agentName(), stage(),
                    "LLM output is null or blank — expected markdown audit + release artifacts.");
        }

        String trimmed = rawOutput.strip();

        if (trimmed.startsWith("{")) {
            try {
                JsonNode probe = objectMapper.readTree(trimmed);
                if (probe.isObject()
                        && (probe.has("security_audit_report")
                        || probe.has("release_artifacts")
                        || probe.has("Dockerfile"))) {
                    // Recognized legacy envelope: validate it and surface any domain violation
                    // (missing Dockerfile, no FROM, missing extension artifacts, …) directly.
                    // Previously a failed legacy validation was swallowed here and the input fell
                    // through to the markdown path, which re-reported it as the misleading generic
                    // "JSON envelope forbidden" message — masking the real, actionable reason.
                    return super.validate(normalizeLegacyJson(trimmed));
                }
            } catch (JsonProcessingException ignored) {
                // Not parseable JSON — fall through to markdown parsing.
            }
        }

        return validateMarkdownOutput(trimmed);
    }

    private String normalizeLegacyJson(String trimmed) throws ValidationHookException {
        try {
            JsonNode root = objectMapper.readTree(trimmed);
            if (!root.isObject()) {
                return trimmed;
            }
            ObjectNode normalized = (ObjectNode) root.deepCopy();
            JsonNode audit = normalized.get("security_audit_report");
            if (audit != null && audit.isTextual()) {
                normalized.putArray("security_audit_report").add(audit.asText());
            }
            return objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            throw new ValidationHookException(agentName(), stage(),
                    "JSON parse error: " + e.getOriginalMessage());
        }
    }

    private JsonNode validateMarkdownOutput(String trimmed) throws ValidationHookException {
        rejectJsonEnvelope(trimmed);

        String deploymentModel = parseDeploymentModel(trimmed);
        List<String> auditFindings = parseAuditFindings(trimmed);
        Map<String, String> releaseArtifacts = parseReleaseArtifacts(trimmed);
        if (releaseArtifacts.isEmpty()) {
            releaseArtifacts.put("package.sh", defaultExtensionPackageScript());
        }

        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode auditArray = root.putArray("security_audit_report");
        auditFindings.forEach(auditArray::add);
        root.put("deployment_model", deploymentModel);
        ObjectNode artifactsNode = root.putObject("release_artifacts");
        releaseArtifacts.forEach(artifactsNode::put);

        List<String> violations = new ArrayList<>();
        collectViolations(root, violations);
        if (!violations.isEmpty()) {
            throw new ValidationHookException(agentName(), stage(), violations);
        }

        log.debug("[{}][{}] Markdown SecOps output validated (deployment_model={}, artifacts={}).",
                agentName(), stage(), deploymentModel, releaseArtifacts.size());
        return root;
    }

    private void rejectJsonEnvelope(String trimmed) {
        if (!trimmed.startsWith("{")) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(trimmed);
            if (root.isObject()
                    && (root.has("security_audit_report") || root.has("release_artifacts"))) {
                throw new ValidationHookException(agentName(), stage(),
                        "JSON envelope forbidden — output audit findings as bullet lines and "
                                + "release artifacts as markdown code blocks only.");
            }
        } catch (JsonProcessingException ignored) {
            // Not JSON — no envelope to reject.
        }
    }

    private String parseDeploymentModel(String text) {
        Matcher matcher = DEPLOYMENT_MODEL_LINE.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).strip().toUpperCase(Locale.ROOT);
        }
        return inferDeploymentModel(text);
    }

    private String inferDeploymentModel(String text) {
        for (MarkdownCodeBlockExtractor.CodeBlock block : MarkdownCodeBlockExtractor.extractAll(text)) {
            String artifactKey = resolveArtifactKey(block, 0);
            if ("Dockerfile".equalsIgnoreCase(artifactKey) && block.content().contains("FROM")) {
                if (hasExtensionArtifactHint(text)) {
                    return "HYBRID";
                }
                return "CONTAINERIZED";
            }
        }
        if (hasExtensionArtifactHint(text)) {
            return "BROWSER_EXTENSION_PACKAGE";
        }
        return "STATIC_DEPLOY";
    }

    private boolean hasExtensionArtifactHint(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("manifest.json")
                || lower.contains("browser extension")
                || lower.contains("chrome extension")
                || lower.contains("web store")
                || lower.contains("extension.zip");
    }

    private List<String> parseAuditFindings(String text) {
        String prose = stripFencedBlocks(text);
        List<String> findings = new ArrayList<>();
        for (String line : prose.split("\\R")) {
            Matcher bullet = AUDIT_BULLET.matcher(line);
            if (bullet.matches()) {
                String item = bullet.group(1).strip();
                if (!item.isBlank() && !item.toUpperCase(Locale.ROOT).startsWith("DEPLOYMENT_MODEL")) {
                    findings.add(item);
                }
            }
        }
        if (findings.isEmpty()) {
            findings.add("INFO: Security audit completed — no critical findings recorded.");
        }
        return findings;
    }

    private Map<String, String> parseReleaseArtifacts(String text) {
        Map<String, String> artifacts = new LinkedHashMap<>();
        List<MarkdownCodeBlockExtractor.CodeBlock> blocks = MarkdownCodeBlockExtractor.extractAll(text);
        int index = 0;
        for (MarkdownCodeBlockExtractor.CodeBlock block : blocks) {
            String key = resolveArtifactKey(block, index++);
            artifacts.put(key, block.content());
        }
        return artifacts;
    }

    private String resolveArtifactKey(MarkdownCodeBlockExtractor.CodeBlock block, int index) {
        String language = block.language();
        if (!language.isBlank()) {
            String[] parts = language.split("\\s+");
            if (parts.length >= 2 && looksLikeFilename(parts[parts.length - 1])) {
                return parts[parts.length - 1];
            }
            if (looksLikeFilename(parts[0])) {
                return parts[0];
            }
            if ("dockerfile".equalsIgnoreCase(parts[0]) || block.content().contains("FROM")) {
                return "Dockerfile";
            }
        }
        if (block.content().contains("FROM ") && block.content().contains("WORKDIR")) {
            return "Dockerfile";
        }
        return "release_artifact_" + (index + 1);
    }

    private boolean looksLikeFilename(String token) {
        return token.contains(".") || "Dockerfile".equalsIgnoreCase(token);
    }

    private String defaultExtensionPackageScript() {
        return """
                #!/bin/bash
                set -euo pipefail
                zip -r extension.zip manifest.json src/
                echo "Packaged extension.zip"
                """;
    }

    private String stripFencedBlocks(String text) {
        return text.replaceAll("(?s)```.*?```", "").strip();
    }

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
