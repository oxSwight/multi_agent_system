package com.midas.d3.llm;

/**
 * Abstraction over any LLM provider (Gemini, OpenAI, Ollama, etc.).
 *
 * <p>Implementations are responsible for:
 * <ul>
 *   <li>Constructing the provider-specific request payload</li>
 *   <li>Handling HTTP transport (timeouts, retries at the HTTP level)</li>
 *   <li>Extracting the text response from the provider-specific response shape</li>
 *   <li>Mapping provider errors to {@link LlmCallException} with correct {@code retryable} flag</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe.
 */
public interface LlmClient {

    /**
     * Calls the LLM and returns the raw text response.
     *
     * <p>The returned string is NOT sanitized — callers must pass it through
     * {@link com.midas.d3.sanitizer.JsonSanitizer#sanitize(String)} before validation.
     *
     * @param request fully constructed call descriptor; must not be null
     * @return raw LLM response text; never null, never blank (throws instead)
     * @throws LlmCallException on any transport error, timeout, or empty response
     */
    String call(LlmCallRequest request) throws LlmCallException;

    /**
     * Returns a short human-readable identifier for this implementation
     * (e.g., {@code "gemini-2.0-flash"}, {@code "ollama/llama3"}).
     */
    String modelId();
}
