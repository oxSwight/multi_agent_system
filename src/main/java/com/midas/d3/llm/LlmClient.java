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
     * Calls the LLM and returns the raw text response with usage metadata.
     *
     * <p>The returned text is NOT sanitized — callers must pass it through
     * {@link com.midas.d3.sanitizer.JsonSanitizer#sanitize(String)} before validation.
     *
     * @param request fully constructed call descriptor; must not be null
     * @return invocation result including raw text, model used, and token counts
     * @throws LlmCallException on any transport error, timeout, or empty response
     */
    LlmCallResult call(LlmCallRequest request) throws LlmCallException;

    /**
     * Returns the configured default model identifier for this client
     * (e.g., {@code "gemini-1.5-flash"}, {@code "ollama/llama3"}).
     *
     * <p>Per-request overrides via {@link LlmCallRequest#getModelOverride()} take precedence at call time.
     */
    String defaultModelId();
}
