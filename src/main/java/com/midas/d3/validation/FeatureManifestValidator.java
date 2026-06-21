package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class FeatureManifestValidator {

    public void validateManifestArray(JsonNode manifestArray, List<String> violations) {
        if (manifestArray == null || manifestArray.isNull() || manifestArray.isMissingNode()) {
            violations.add("Missing required array field: 'feature_manifest'.");
            return;
        }
        if (!manifestArray.isArray()) {
            violations.add("Field 'feature_manifest' must be an array.");
            return;
        }
        if (manifestArray.isEmpty()) {
            violations.add("Array 'feature_manifest' must have at least 1 item(s), found 0.");
            return;
        }

        Set<String> seenFeatureIds = new HashSet<>();
        for (int i = 0; i < manifestArray.size(); i++) {
            JsonNode entry = manifestArray.get(i);
            String prefix = "feature_manifest[" + i + "]";
            if (!entry.isObject()) {
                violations.add(prefix + " must be a JSON object.");
                continue;
            }
            validateEntry(entry, prefix, seenFeatureIds, violations);
        }
    }

    private void validateEntry(JsonNode entry,
                               String prefix,
                               Set<String> seenFeatureIds,
                               List<String> violations) {
        JsonNode featureIdNode = entry.get("feature_id");
        if (featureIdNode == null || featureIdNode.isNull() || featureIdNode.isMissingNode()) {
            violations.add(prefix + " missing required field: 'feature_id'.");
        } else if (!featureIdNode.isTextual() || featureIdNode.asText().isBlank()) {
            violations.add(prefix + ".feature_id must be a non-blank string.");
        } else {
            String featureId = featureIdNode.asText().strip();
            if (!seenFeatureIds.add(featureId)) {
                violations.add(prefix + ".feature_id '" + featureId + "' is duplicated in feature_manifest.");
            }
        }

        JsonNode featureNameNode = entry.get("feature_name");
        if (featureNameNode == null || featureNameNode.isNull() || featureNameNode.isMissingNode()) {
            violations.add(prefix + " missing required field: 'feature_name'.");
        } else if (!featureNameNode.isTextual() || featureNameNode.asText().isBlank()) {
            violations.add(prefix + ".feature_name must be a non-blank string.");
        }

        validateNonEmptyStringArray(entry.get("files"), prefix + ".files", violations);
        validateNonEmptyStringArray(entry.get("entry_points"), prefix + ".entry_points", violations);
    }

    private void validateNonEmptyStringArray(JsonNode arrayNode,
                                             String fieldLabel,
                                             List<String> violations) {
        if (arrayNode == null || arrayNode.isNull() || arrayNode.isMissingNode()) {
            violations.add("Missing required array field: '" + fieldLabel + "'.");
            return;
        }
        if (!arrayNode.isArray()) {
            violations.add("Field '" + fieldLabel + "' must be an array.");
            return;
        }
        if (arrayNode.isEmpty()) {
            violations.add("Array '" + fieldLabel + "' must have at least 1 item(s), found 0.");
            return;
        }
        for (int i = 0; i < arrayNode.size(); i++) {
            JsonNode item = arrayNode.get(i);
            if (!item.isTextual() || item.asText().isBlank()) {
                violations.add(fieldLabel + "[" + i + "] must be a non-blank string.");
            }
        }
    }
}
