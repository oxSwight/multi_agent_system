package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Objects;

public final class SourceMapPathFilter {

    private SourceMapPathFilter() {}

    public static JsonNode filter(JsonNode sourceMap, List<String> paths, ObjectMapper mapper) {
        Objects.requireNonNull(sourceMap, "sourceMap must not be null");
        Objects.requireNonNull(paths, "paths must not be null");
        Objects.requireNonNull(mapper, "mapper must not be null");

        if (!sourceMap.isObject()) {
            return mapper.createObjectNode();
        }

        ObjectNode filtered = mapper.createObjectNode();
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            JsonNode node = sourceMap.get(path);
            if (node != null && !node.isNull() && !node.isMissingNode()) {
                filtered.set(path, node);
            }
        }
        return filtered;
    }
}
