package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * Merges client and server implementation pass outputs into a single
 * {@code generatedSourceCode} map for downstream stages.
 */
public final class ImplementationSourceMerger {

    private ImplementationSourceMerger() {}

    /**
     * @throws ImplementationMergeException when both passes emit the same file path
     */
    public static JsonNode merge(JsonNode clientOutput, JsonNode serverOutput, ObjectMapper mapper) {
        requireSourceMap(clientOutput, "client");
        requireSourceMap(serverOutput, "server");

        ObjectNode merged = mapper.createObjectNode();
        copyEntries(clientOutput, merged, "client");
        copyEntries(serverOutput, merged, "server");
        return merged;
    }

    private static void requireSourceMap(JsonNode node, String passLabel) {
        if (node == null || !node.isObject()) {
            throw new ImplementationMergeException(
                    "HYBRID merge failed: " + passLabel + " pass output must be a JSON object map.");
        }
        if (node.isEmpty()) {
            throw new ImplementationMergeException(
                    "HYBRID merge failed: " + passLabel + " pass produced an empty source map.");
        }
    }

    private static void copyEntries(JsonNode source, ObjectNode target, String passLabel) {
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String path = entry.getKey();
            if (path.isBlank()) {
                throw new ImplementationMergeException(
                        "HYBRID merge failed: " + passLabel + " pass contained a blank file path.");
            }
            if (target.has(path)) {
                throw new ImplementationMergeException(
                        "HYBRID merge failed: duplicate file path [" + path + "] in client and server passes.");
            }
            target.set(path, entry.getValue());
        }
    }

    public static final class ImplementationMergeException extends RuntimeException {
        public ImplementationMergeException(String message) {
            super(message);
        }
    }
}
