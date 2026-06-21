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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ImplementationEngineerValidator extends AbstractGoalKeeperValidator {

    private static final Pattern MARKDOWN_FENCE = Pattern.compile(
            "```([a-zA-Z0-9_-]+)?\\s*\\r?\\n(.*?)\\r?\\n?```",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

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

    /**
     * Extracts and validates raw source from a per-file LLM response.
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

        String content = extractMarkdownCodeBlock(trimmed);
        if (content == null || content.isBlank()) {
            throw new ValidationHookException(agentName(), stage(),
                    "LLM output must be a single markdown code block (```language ... ```) "
                            + "containing the complete source for [" + expectedPath + "].");
        }

        List<String> violations = new java.util.ArrayList<>();
        rejectPlaceholders(expectedPath, content, violations);
        if (!violations.isEmpty()) {
            throw new ValidationHookException(agentName(), stage(), violations);
        }
        return content;
    }

    static String extractMarkdownCodeBlock(String text) {
        Matcher matcher = MARKDOWN_FENCE.matcher(text);
        String fallback = null;
        while (matcher.find()) {
            String extracted = matcher.group(2);
            if (extracted == null) {
                continue;
            }
            String stripped = extracted.strip();
            if (stripped.isBlank()) {
                continue;
            }
            if (!stripped.startsWith("{")) {
                return stripped;
            }
            if (fallback == null) {
                fallback = stripped;
            }
        }
        return fallback;
    }

    private void rejectJsonEnvelope(String trimmed) {
        if (!trimmed.startsWith("{")) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(trimmed);
            if (root.isObject() && (root.has("path") || root.has("content") || root.has("source_files"))) {
                throw new ValidationHookException(agentName(), stage(),
                        "JSON envelope forbidden — output raw source in a single markdown code block only.");
            }
        } catch (JsonProcessingException ignored) {
            // Not JSON — no envelope to reject.
        }
    }

    public JsonNode validatePatchOutput(String rawJson, List<String> allowedPaths)
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

        JsonNode sourceFiles = root.get("source_files");
        if (sourceFiles == null || !sourceFiles.isObject()) {
            throw new ValidationHookException(agentName(), stage(),
                    "Patch output must contain a 'source_files' object.");
        }

        Set<String> allowed = new HashSet<>(allowedPaths);
        List<String> violations = new java.util.ArrayList<>();
        if (sourceFiles.isEmpty()) {
            violations.add("Patch source_files map must not be empty.");
        }
        validateSourceFiles(sourceFiles, violations);

        Iterator<Map.Entry<String, JsonNode>> fields = sourceFiles.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!allowed.contains(entry.getKey())) {
                violations.add("Patch source_files contains disallowed path [" + entry.getKey()
                        + "] — only affected_paths are permitted.");
            }
        }

        if (!violations.isEmpty()) {
            throw new ValidationHookException(agentName(), stage(), violations);
        }

        return sourceFiles;
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

        Set<String> requiredIds = new HashSet<>();
        Set<String> requiredNames = new HashSet<>();
        Set<String> derivedTextualIds = new HashSet<>();
        for (int i = 0; i < coreFeatures.size(); i++) {
            JsonNode featureNode = coreFeatures.get(i);
            if (featureNode.isTextual()) {
                String featureLabel = featureNode.asText().strip();
                if (featureLabel.isBlank()) {
                    continue;
                }
                requiredNames.add(featureLabel);
                derivedTextualIds.add(toFeatureId(featureLabel));
            } else if (featureNode.isObject()) {
                JsonNode idNode = featureNode.get("id");
                if (idNode != null && idNode.isTextual() && !idNode.asText().isBlank()) {
                    requiredIds.add(idNode.asText().strip());
                }
                JsonNode nameNode = featureNode.get("name");
                if (nameNode != null && nameNode.isTextual() && !nameNode.asText().isBlank()) {
                    requiredNames.add(nameNode.asText().strip());
                }
            }
        }

        Set<String> matchedRequiredIds = new HashSet<>();
        Set<String> matchedRequiredNames = new HashSet<>();
        Set<String> matchedDerivedTextualIds = new HashSet<>();

        for (int i = 0; i < featureManifest.size(); i++) {
            JsonNode entry = featureManifest.get(i);
            if (!entry.isObject()) {
                continue;
            }
            String manifestId = "";
            JsonNode idNode = entry.get("feature_id");
            if (idNode != null && idNode.isTextual() && !idNode.asText().isBlank()) {
                manifestId = idNode.asText().strip();
            }
            String manifestName = "";
            JsonNode nameNode = entry.get("feature_name");
            if (nameNode != null && nameNode.isTextual() && !nameNode.asText().isBlank()) {
                manifestName = nameNode.asText().strip();
            }

            boolean matched = false;
            if (!manifestId.isBlank() && requiredIds.contains(manifestId)) {
                matchedRequiredIds.add(manifestId);
                matched = true;
            }
            if (!manifestName.isBlank() && requiredNames.contains(manifestName)) {
                matchedRequiredNames.add(manifestName);
                matched = true;
            }
            if (!manifestId.isBlank() && derivedTextualIds.contains(manifestId)) {
                matchedDerivedTextualIds.add(manifestId);
                matched = true;
            }

            if (!matched && !manifestId.isBlank()) {
                violations.add("feature_manifest feature_id [" + manifestId
                        + "] does not match any core_features id.");
            }
        }

        for (String requiredId : requiredIds) {
            if (!matchedRequiredIds.contains(requiredId)) {
                violations.add("core_features id [" + requiredId + "] is missing from feature_manifest.");
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
            if (!matchedDerivedTextualIds.contains(expectedId) && !matchedRequiredNames.contains(featureLabel)) {
                violations.add("core_features [" + featureLabel + "] is not represented in feature_manifest.");
            }
        }
    }

    public static String toFeatureId(String featureLabel) {
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
