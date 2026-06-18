package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FeatureManifestMerger {

    private FeatureManifestMerger() {}

    public static JsonNode merge(JsonNode clientManifest, JsonNode serverManifest, ObjectMapper mapper) {
        requireManifestArray(clientManifest, "client");
        requireManifestArray(serverManifest, "server");

        Map<String, JsonNode> mergedByFeatureId = new LinkedHashMap<>();
        absorbEntries(clientManifest, "client", mergedByFeatureId);
        absorbEntries(serverManifest, "server", mergedByFeatureId);

        ArrayNode merged = mapper.createArrayNode();
        mergedByFeatureId.values().forEach(merged::add);
        return merged;
    }

    private static void requireManifestArray(JsonNode node, String passLabel) {
        if (node == null || !node.isArray()) {
            throw new FeatureManifestMergeException(
                    "HYBRID manifest merge failed: " + passLabel + " pass feature_manifest must be a JSON array.");
        }
        if (node.isEmpty()) {
            throw new FeatureManifestMergeException(
                    "HYBRID manifest merge failed: " + passLabel + " pass feature_manifest must not be empty.");
        }
    }

    private static void absorbEntries(JsonNode manifestArray,
                                    String passLabel,
                                    Map<String, JsonNode> mergedByFeatureId) {
        for (int i = 0; i < manifestArray.size(); i++) {
            JsonNode entry = manifestArray.get(i);
            if (!entry.isObject()) {
                throw new FeatureManifestMergeException(
                        "HYBRID manifest merge failed: " + passLabel + " pass feature_manifest[" + i + "] must be an object.");
            }
            JsonNode featureIdNode = entry.get("feature_id");
            if (featureIdNode == null || !featureIdNode.isTextual() || featureIdNode.asText().isBlank()) {
                throw new FeatureManifestMergeException(
                        "HYBRID manifest merge failed: " + passLabel + " pass feature_manifest[" + i + "] missing feature_id.");
            }
            String featureId = featureIdNode.asText().strip();
            JsonNode existing = mergedByFeatureId.get(featureId);
            if (existing != null) {
                if (!existing.equals(entry)) {
                    throw new FeatureManifestMergeException(
                            "HYBRID manifest merge failed: conflicting entries for feature_id [" + featureId + "].");
                }
                continue;
            }
            mergedByFeatureId.put(featureId, entry);
        }
    }

    public static final class FeatureManifestMergeException extends RuntimeException {
        public FeatureManifestMergeException(String message) {
            super(message);
        }
    }
}
