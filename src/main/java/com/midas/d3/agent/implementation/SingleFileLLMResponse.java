package com.midas.d3.agent.implementation;

/**
 * Validated LLM response for a single per-file CODE_GENERATION call.
 * The pipeline supplies {@code path}; the LLM returns raw source inside a markdown code block.
 */
public record SingleFileLLMResponse(String path, String content) {}
