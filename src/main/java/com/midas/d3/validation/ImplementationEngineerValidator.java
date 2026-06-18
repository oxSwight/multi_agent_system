package com.midas.d3.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class ImplementationEngineerValidator extends AbstractGoalKeeperValidator {

    private final FeatureManifestValidator featureManifestValidator;

    public ImplementationEngineerValidator(ObjectMapper objectMapper,
                                             FeatureManifestValidator featureManifestValidator) {
        super(objectMapper);
        this.featureManifestValidator = featureManifestValidator;
    }

    @Override public String agentName() { return "ImplementationEngineer"; }
    @Override public String stage()     { return "CODE_GENERATION"; }

    public JsonNode validateWithTechnicalSpec(String rawJson, JsonNode technicalSpec)
            throws ValidationHookException {
        if (rawJson == null || rawJson.isBlank()) {
            throw new ValidationHookException(agentName(), stage(),
                    "LLM output is null or blank — no JSON to validate.");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawJson.strip());
        } catch (JsonProcessingException e) {
            throw new ValidationHookException(agentName(), stage(),
                    "JSON parse error: " + e.getOriginalMessage());
        }

        if (!root.isObject()) {
            throw new ValidationHookException(agentName(), stage(),
                    "Expected JSON object at root, got: " + root.getNodeType());
        }

        List<String> violations = new java.util.ArrayList<>();
        collectViolations(root, violations, technicalSpec);

        if (!violations.isEmpty()) {
            throw new ValidationHookException(agentName(), stage(), violations);
        }

        return root;
    }

    @Override
    protected void collectViolations(JsonNode root, List<String> violations) {
        collectViolations(root, violations, null);
    }

    private void collectViolations(JsonNode root, List<String> violations, JsonNode technicalSpec) {
        if (root.has("source_files") || root.has("feature_manifest")) {
            validateEnvelope(root, violations, technicalSpec);
            return;
        }

        violations.add("Implementation output must use the envelope schema with 'source_files' and 'feature_manifest'.");
    }

    private void validateEnvelope(JsonNode root, List<String> violations, JsonNode technicalSpec) {
        JsonNode sourceFiles = root.get("source_files");
        JsonNode featureManifest = root.get("feature_manifest");

        requireObjectField(root, "source_files", violations);
        requireArrayField(root, "feature_manifest", 1, violations);

        if (sourceFiles != null && sourceFiles.isObject()) {
            validateSourceFiles(sourceFiles, violations);
        }

        if (featureManifest != null && featureManifest.isArray()) {
            featureManifestValidator.validateManifestArray(featureManifest, violations);
            if (sourceFiles != null && sourceFiles.isObject()) {
                crossCheckManifestFiles(sourceFiles, featureManifest, violations);
            }
            if (technicalSpec != null && !technicalSpec.isNull() && !technicalSpec.isMissingNode()) {
                crossCheckManifestAgainstSpec(featureManifest, technicalSpec, violations);
            }
        }
    }

    private void validateSourceFiles(JsonNode sourceFiles, List<String> violations) {
        if (sourceFiles.isEmpty()) {
            violations.add("Generated source code map must not be empty.");
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = sourceFiles.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String fileName = entry.getKey();
            JsonNode value = entry.getValue();
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

    private void crossCheckManifestFiles(JsonNode sourceFiles,
                                         JsonNode featureManifest,
                                         List<String> violations) {
        Set<String> sourcePaths = new HashSet<>();
        sourceFiles.fieldNames().forEachRemaining(sourcePaths::add);

        for (int i = 0; i < featureManifest.size(); i++) {
            JsonNode entry = featureManifest.get(i);
            if (!entry.isObject()) {
                continue;
            }
            JsonNode filesNode = entry.get("files");
            if (filesNode == null || !filesNode.isArray()) {
                continue;
            }
            for (int j = 0; j < filesNode.size(); j++) {
                JsonNode fileNode = filesNode.get(j);
                if (!fileNode.isTextual()) {
                    continue;
                }
                String filePath = fileNode.asText().strip();
                if (filePath.isBlank()) {
                    continue;
                }
                if (!sourcePaths.contains(filePath)) {
                    violations.add("feature_manifest[" + i + "].files[" + j + "] references ["
                            + filePath + "] which is not present in source_files.");
                }
            }
        }
    }

    private void crossCheckManifestAgainstSpec(JsonNode featureManifest,
                                               JsonNode technicalSpec,
                                               List<String> violations) {
        JsonNode coreFeatures = technicalSpec.get("core_features");
        if (coreFeatures == null || !coreFeatures.isArray() || coreFeatures.isEmpty()) {
            return;
        }

        Set<String> manifestIds = new HashSet<>();
        Set<String> manifestNames = new HashSet<>();
        for (int i = 0; i < featureManifest.size(); i++) {
            JsonNode entry = featureManifest.get(i);
            if (!entry.isObject()) {
                continue;
            }
            JsonNode idNode = entry.get("feature_id");
            if (idNode != null && idNode.isTextual() && !idNode.asText().isBlank()) {
                manifestIds.add(idNode.asText().strip());
            }
            JsonNode nameNode = entry.get("feature_name");
            if (nameNode != null && nameNode.isTextual() && !nameNode.asText().isBlank()) {
                manifestNames.add(nameNode.asText().strip());
            }
        }

        Set<String> requiredIds = new HashSet<>();
        for (int i = 0; i < coreFeatures.size(); i++) {
            JsonNode featureNode = coreFeatures.get(i);
            if (featureNode.isTextual()) {
                String featureLabel = featureNode.asText().strip();
                if (featureLabel.isBlank()) {
                    continue;
                }
                requiredIds.add(toFeatureId(featureLabel));
            } else if (featureNode.isObject()) {
                JsonNode idNode = featureNode.get("id");
                if (idNode != null && idNode.isTextual() && !idNode.asText().isBlank()) {
                    requiredIds.add(idNode.asText().strip());
                }
            }
        }

        for (String requiredId : requiredIds) {
            if (!manifestIds.contains(requiredId)) {
                violations.add("core_features id [" + requiredId + "] is missing from feature_manifest.");
            }
        }

        for (String manifestId : manifestIds) {
            if (!requiredIds.contains(manifestId)) {
                violations.add("feature_manifest feature_id [" + manifestId
                        + "] does not match any core_features id.");
            }
        }

        for (int i = 0; i < coreFeatures.size(); i++) {
            JsonNode featureNode = coreFeatures.get(i);
            if (!featureNode.isTextual()) {
                continue;
            }
            String featureLabel = featureNode.asText().strip();
            if (featureLabel.isBlank()) {
                continue;
            }
            String expectedId = toFeatureId(featureLabel);
            if (!manifestIds.contains(expectedId) && !manifestNames.contains(featureLabel)) {
                violations.add("core_features [" + featureLabel + "] is not represented in feature_manifest.");
            }
        }
    }

    static String toFeatureId(String featureLabel) {
        String normalized = featureLabel.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("-+", "-");
        if (normalized.startsWith("-")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("-")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? featureLabel.toLowerCase(Locale.ROOT) : normalized;
    }
}
