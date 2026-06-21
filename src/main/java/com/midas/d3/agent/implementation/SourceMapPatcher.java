package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

public final class SourceMapPatcher {

    private SourceMapPatcher() {}

    public static JsonNode apply(JsonNode baseline, JsonNode patch, ObjectMapper mapper) {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(patch, "patch must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");

        if (!patch.isObject() || patch.isEmpty()) {
            throw new PatchValidationException("Patch map must be a non-empty JSON object");
        }

        Iterator<Map.Entry<String, JsonNode>> patchFields = patch.fields();
        while (patchFields.hasNext()) {
            Map.Entry<String, JsonNode> entry = patchFields.next();
            String path = entry.getKey();
            JsonNode value = entry.getValue();

            if (path == null || path.isBlank()) {
                throw new PatchValidationException("Patch contains an illegal blank path key");
            }
            if (path.contains("..")) {
                throw new PatchValidationException(
                        "Patch contains an illegal path with traversal: " + path);
            }
            if (value == null || value.isNull() || value.isMissingNode()) {
                throw new PatchValidationException(
                        "Patch value for path [" + path + "] is null or missing; destructive operations are not supported");
            }
            if (!baseline.isObject() || !baseline.has(path)) {
                throw new PatchValidationException(
                        "Patch references unknown path [" + path + "] not present in baseline source map");
            }
        }

        ObjectNode merged = mapper.createObjectNode();
        baseline.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue()));
        patch.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue()));
        return merged;
    }
}
