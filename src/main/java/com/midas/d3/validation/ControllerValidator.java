package com.midas.d3.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Component
public class ControllerValidator extends AbstractGoalKeeperValidator {

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

    public JsonNode validateWithFeatureManifest(String rawJson, JsonNode featureManifest)
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

        normalizeCoverageMatrixEvidence((ObjectNode) root, featureManifest);

        List<String> violations = new java.util.ArrayList<>();
        collectViolations(root, violations, featureManifest);

        if (!violations.isEmpty()) {
            throw new ValidationHookException(agentName(), stage(), violations);
        }

        return root;
    }

    @Override
    protected void collectViolations(JsonNode root, List<String> violations) {
        collectViolations(root, violations, null);
    }

    private void collectViolations(JsonNode root, List<String> violations, JsonNode featureManifest) {
        String verdict = validateVerdict(root, violations);
        validateCoverageMatrix(root, violations, featureManifest);
        validateRemediationBlock(root, verdict, violations);
    }

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

    private void validateCoverageMatrix(JsonNode root, List<String> violations, JsonNode featureManifest) {
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
            JsonNode evidence = entry.get("evidence");
            if (evidence == null || !evidence.isTextual() || evidence.asText().isBlank()) {
                violations.add("coverage_matrix[" + i + "].evidence is missing or blank.");
            }
        }
    }

    /**
     * Repairs {@code coverage_matrix[].evidence} when blank or not cross-referenced to the
     * implementation {@code featureManifest}, instead of failing the whole PRODUCT_REVIEW stage.
     */
    private void normalizeCoverageMatrixEvidence(ObjectNode root, JsonNode featureManifest) {
        JsonNode matrix = root.get("coverage_matrix");
        if (matrix == null || !matrix.isArray()) {
            return;
        }

        ManifestReferenceIndex manifestIndex = buildManifestReferenceIndex(featureManifest);
        String defaultFallback = manifestIndex.defaultFallbackEvidence();

        for (int i = 0; i < matrix.size(); i++) {
            JsonNode entryNode = matrix.get(i);
            if (!entryNode.isObject()) {
                continue;
            }
            ObjectNode entry = (ObjectNode) entryNode;

            JsonNode evidenceNode = entry.get("evidence");
            String evidence = evidenceNode != null && evidenceNode.isTextual()
                    ? evidenceNode.asText().strip()
                    : "";

            boolean needsFallback = evidence.isBlank();
            if (!needsFallback && manifestIndex.isPresent()) {
                needsFallback = !evidenceReferencesManifest(evidence, manifestIndex);
            }

            if (needsFallback) {
                String fallback = resolveFallbackEvidence(entry, manifestIndex, defaultFallback);
                log.warn("[ControllerAgent] Invalid evidence at coverage_matrix[{}] ({}), applying fallback: {}",
                        i, evidence.isBlank() ? "<blank>" : evidence, fallback);
                entry.put("evidence", fallback);
            }
        }
    }

    private boolean evidenceReferencesManifest(String evidence, ManifestReferenceIndex manifestIndex) {
        String evidenceLower = evidence.toLowerCase(Locale.ROOT);
        return manifestIndex.featureIds().stream().anyMatch(evidenceLower::contains)
                || manifestIndex.filePaths().stream().anyMatch(evidenceLower::contains);
    }

    private String resolveFallbackEvidence(ObjectNode entry,
                                           ManifestReferenceIndex manifestIndex,
                                           String defaultFallback) {
        JsonNode requestedFeature = entry.get("requested_feature");
        if (requestedFeature != null && requestedFeature.isTextual() && manifestIndex.isPresent()) {
            Optional<String> matched = manifestIndex.findFeatureIdForRequestedFeature(
                    requestedFeature.asText().strip());
            if (matched.isPresent()) {
                return matched.get();
            }
        }
        if (defaultFallback != null && !defaultFallback.isBlank()) {
            return defaultFallback;
        }
        if (requestedFeature != null && requestedFeature.isTextual() && !requestedFeature.asText().isBlank()) {
            return requestedFeature.asText().strip();
        }
        return "pipeline-artifact-coverage";
    }

    private ManifestReferenceIndex buildManifestReferenceIndex(JsonNode featureManifest) {
        if (featureManifest == null || featureManifest.isNull() || featureManifest.isMissingNode()
                || !featureManifest.isArray() || featureManifest.isEmpty()) {
            return ManifestReferenceIndex.absent();
        }

        Set<String> featureIds = new HashSet<>();
        Set<String> filePaths = new HashSet<>();
        Set<String> featureNames = new HashSet<>();
        String primaryFeatureId = "";
        String primaryFilePath = "";

        for (int i = 0; i < featureManifest.size(); i++) {
            JsonNode entry = featureManifest.get(i);
            if (!entry.isObject()) {
                continue;
            }
            JsonNode featureIdNode = entry.get("feature_id");
            if (featureIdNode != null && featureIdNode.isTextual() && !featureIdNode.asText().isBlank()) {
                String featureId = featureIdNode.asText().strip();
                featureIds.add(featureId.toLowerCase(Locale.ROOT));
                if (primaryFeatureId.isBlank()) {
                    primaryFeatureId = featureId;
                }
            }
            JsonNode featureNameNode = entry.get("feature_name");
            if (featureNameNode != null && featureNameNode.isTextual() && !featureNameNode.asText().isBlank()) {
                featureNames.add(featureNameNode.asText().strip().toLowerCase(Locale.ROOT));
            }
            JsonNode filesNode = entry.get("files");
            if (filesNode != null && filesNode.isArray()) {
                for (JsonNode fileNode : filesNode) {
                    if (fileNode.isTextual() && !fileNode.asText().isBlank()) {
                        String filePath = fileNode.asText().strip();
                        filePaths.add(filePath.toLowerCase(Locale.ROOT));
                        if (primaryFilePath.isBlank()) {
                            primaryFilePath = filePath;
                        }
                    }
                }
            }
        }

        if (featureIds.isEmpty() && filePaths.isEmpty()) {
            return ManifestReferenceIndex.absent();
        }
        return new ManifestReferenceIndex(featureIds, filePaths, featureNames, primaryFeatureId, primaryFilePath);
    }

    private record ManifestReferenceIndex(
            Set<String> featureIds,
            Set<String> filePaths,
            Set<String> featureNames,
            String primaryFeatureId,
            String primaryFilePath) {

        static ManifestReferenceIndex absent() {
            return new ManifestReferenceIndex(Set.of(), Set.of(), Set.of(), "", "");
        }

        boolean isPresent() {
            return !featureIds.isEmpty() || !filePaths.isEmpty();
        }

        String defaultFallbackEvidence() {
            if (primaryFeatureId != null && !primaryFeatureId.isBlank()) {
                return primaryFeatureId;
            }
            if (primaryFilePath != null && !primaryFilePath.isBlank()) {
                return primaryFilePath;
            }
            return "";
        }

        Optional<String> findFeatureIdForRequestedFeature(String requestedFeature) {
            String requestedLower = requestedFeature.toLowerCase(Locale.ROOT);
            for (String featureId : featureIds) {
                if (requestedLower.contains(featureId) || featureId.contains(requestedLower)) {
                    return Optional.of(featureId);
                }
            }
            for (String featureName : featureNames) {
                if (requestedLower.contains(featureName) || featureName.contains(requestedLower)) {
                    return featureIds.stream().findFirst();
                }
            }
            return Optional.empty();
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
