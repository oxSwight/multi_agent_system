package com.midas.d3.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * Shared helper for combining the generated source and test path→contents maps into one map — the
 * single shape build verification and the quality score both consume. Centralized so the merge
 * semantics ("source first; a test never clobbers a production file of the same path") have one
 * definition instead of being copied per call site.
 */
public final class SourceMaps {

    private SourceMaps() {
    }

    /** Merges {@code source} then overlays {@code tests} without overwriting an existing source path. */
    public static JsonNode merge(JsonNode source, JsonNode tests, ObjectMapper mapper) {
        ObjectNode merged = mapper.createObjectNode();
        copyInto(merged, source);
        copyInto(merged, tests);
        return merged;
    }

    private static void copyInto(ObjectNode target, JsonNode source) {
        if (source == null || !source.isObject()) {
            return;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = source.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!target.has(e.getKey())) {
                target.set(e.getKey(), e.getValue());
            }
        }
    }
}
