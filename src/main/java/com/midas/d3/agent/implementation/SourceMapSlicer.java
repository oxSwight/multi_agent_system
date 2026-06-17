package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

public final class SourceMapSlicer {

    private SourceMapSlicer() {}

    public static JsonNode slice(JsonNode sourceMap, ImplementationSurface surface, ObjectMapper mapper) {
        if (sourceMap == null || !sourceMap.isObject()) {
            return mapper.createObjectNode();
        }
        ObjectNode sliced = mapper.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = sourceMap.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String path = entry.getKey();
            boolean include = surface == ImplementationSurface.CLIENT
                    ? ArchitectureSurfaceSlicer.isClientPath(path)
                    : ArchitectureSurfaceSlicer.isServerPath(path);
            if (include) {
                sliced.set(path, entry.getValue());
            }
        }
        return sliced;
    }
}
