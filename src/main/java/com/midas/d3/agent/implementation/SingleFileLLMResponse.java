package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.validation.ValidationHookException;

/**
 * Parsed LLM response for a single per-file CODE_GENERATION call.
 * Schema: {@code {"path":"<relative/path>","content":"<file body>"}}.
 */
public record SingleFileLLMResponse(String path, String content) {

    public static SingleFileLLMResponse parse(String rawJson, String expectedPath, ObjectMapper objectMapper)
            throws ValidationHookException {
        if (rawJson == null || rawJson.isBlank()) {
            throw new ValidationHookException("ImplementationEngineer", "CODE_GENERATION",
                    "LLM output is null or blank — no JSON to validate.");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawJson.strip());
        } catch (JsonProcessingException e) {
            throw new ValidationHookException("ImplementationEngineer", "CODE_GENERATION",
                    "JSON parse error: " + e.getOriginalMessage());
        }

        if (!root.isObject()) {
            throw new ValidationHookException("ImplementationEngineer", "CODE_GENERATION",
                    "Expected JSON object at root, got: " + root.getNodeType());
        }

        JsonNode pathNode = root.get("path");
        if (pathNode == null || !pathNode.isTextual() || pathNode.asText().isBlank()) {
            throw new ValidationHookException("ImplementationEngineer", "CODE_GENERATION",
                    "Single-file output must contain a non-blank 'path' string.");
        }

        JsonNode contentNode = root.get("content");
        if (contentNode == null || !contentNode.isTextual() || contentNode.asText().isBlank()) {
            throw new ValidationHookException("ImplementationEngineer", "CODE_GENERATION",
                    "Single-file output must contain non-blank 'content' string for path ["
                            + pathNode.asText() + "].");
        }

        String path = pathNode.asText().strip();
        if (!path.equals(expectedPath)) {
            throw new ValidationHookException("ImplementationEngineer", "CODE_GENERATION",
                    "Single-file output path [" + path + "] does not match requested path [" + expectedPath + "].");
        }

        return new SingleFileLLMResponse(path, contentNode.asText());
    }
}
