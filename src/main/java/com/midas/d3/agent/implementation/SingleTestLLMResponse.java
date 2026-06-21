package com.midas.d3.agent.implementation;

/**
 * Validated LLM response for a single per-file TEST_GENERATION call.
 * The pipeline supplies {@code path}; the LLM returns raw test source inside a markdown code block.
 */
public record SingleTestLLMResponse(String path, String content) {}
