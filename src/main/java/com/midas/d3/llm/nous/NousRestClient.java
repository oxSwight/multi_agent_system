package com.midas.d3.llm.nous;

import com.midas.d3.llm.LlmCallException;
import com.midas.d3.llm.LlmCallRequest;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.llm.nous.dto.NousRequest;
import com.midas.d3.llm.nous.dto.NousResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * OpenAI-compatible LLM client for NousResearch models.
 *
 * <p>Supports any OpenAI-compatible endpoint:
 * <ul>
 *   <li><b>OpenRouter (cloud):</b> set {@code midas.nous.base-url=https://openrouter.ai/api}
 *       and {@code midas.nous.api-key=sk-or-...}</li>
 *   <li><b>LM Studio (local):</b> set {@code midas.nous.base-url=http://localhost:1234}
 *       (no API key required)</li>
 * </ul>
 *
 * <p><b>Retry policy:</b>
 * <ul>
 *   <li>Up to {@code midas.nous.http-max-retries} attempts (default: 2)</li>
 *   <li>Exponential backoff starting at 2 s, max 30 s, jitter 50 %</li>
 *   <li>Retries only on: HTTP 429, HTTP 5xx, {@link java.util.concurrent.TimeoutException},
 *       and {@link java.io.IOException}</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> {@link WebClient} is inherently thread-safe.
 */
@Slf4j
@Component
public class NousRestClient implements LlmClient {

    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    private final WebClient webClient;
    private final String    model;
    private final int       timeoutSeconds;
    private final int       httpMaxRetries;

    public NousRestClient(
            WebClient.Builder webClientBuilder,
            @Value("${midas.nous.base-url:http://localhost:1234}") String baseUrl,
            @Value("${midas.nous.api-key:}") String apiKey,
            @Value("${midas.nous.model:nous-hermes-2-mistral-7b-dpo}") String model,
            @Value("${midas.nous.timeout-seconds:120}") int timeoutSeconds,
            @Value("${midas.nous.http-max-retries:2}") int httpMaxRetries) {

        this.model          = model;
        this.timeoutSeconds = timeoutSeconds;
        this.httpMaxRetries = Math.max(0, httpMaxRetries);

        WebClient.Builder builder = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT,       MediaType.APPLICATION_JSON_VALUE);

        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }

        this.webClient = builder.build();
        log.info("[NousRestClient] Initialized: baseUrl={}, model={}, timeoutSeconds={}, maxRetries={}",
                baseUrl, model, timeoutSeconds, this.httpMaxRetries);
    }

    // ── LlmClient ─────────────────────────────────────────────────────────────

    @Override
    public String call(LlmCallRequest request) throws LlmCallException {
        NousRequest body = NousRequest.of(model, request.getSystemPrompt(), request.getUserMessage());

        log.info("[NousRestClient] Calling agent=[{}] run=[{}] model={}",
                request.getAgentName(), request.getPipelineRunId(), model);

        try {
            NousResponse response = webClient.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(NousResponse.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .retryWhen(Retry.backoff(httpMaxRetries, Duration.ofSeconds(2))
                            .maxBackoff(Duration.ofSeconds(30))
                            .jitter(0.5)
                            .filter(this::isRetryable)
                            .doBeforeRetry(signal ->
                                    log.warn("[NousRestClient][{}] Retry attempt {} due to: {}",
                                            request.getAgentName(),
                                            signal.totalRetries() + 1,
                                            signal.failure().getClass().getSimpleName())))
                    .block();

            if (response == null) {
                throw LlmCallException.emptyResponse(request.getAgentName());
            }

            return response.extractText()
                    .orElseThrow(() -> LlmCallException.emptyResponse(request.getAgentName()));

        } catch (LlmCallException e) {
            throw e;
        } catch (WebClientResponseException e) {
            log.error("[NousRestClient][{}] HTTP {} — {}",
                    request.getAgentName(), e.getStatusCode(), e.getResponseBodyAsString());
            throw mapHttpError(request.getAgentName(), e);
        } catch (Exception e) {
            // Unwrap ReactorException wrapping a TimeoutException
            if (isTimeoutCause(e)) {
                log.error("[NousRestClient][{}] Request timed out after {} s.",
                        request.getAgentName(), timeoutSeconds);
                throw LlmCallException.timeout(request.getAgentName());
            }
            log.error("[NousRestClient][{}] Unexpected error.", request.getAgentName(), e);
            throw new LlmCallException(
                    "Unexpected error from NousRestClient for agent [%s]: %s"
                            .formatted(request.getAgentName(), e.getMessage()),
                    e, true);
        }
    }

    @Override
    public String modelId() {
        return model;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean isRetryable(Throwable t) {
        if (t instanceof WebClientResponseException e) {
            int status = e.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        return t instanceof java.util.concurrent.TimeoutException
                || t instanceof java.io.IOException;
    }

    private boolean isTimeoutCause(Throwable t) {
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof java.util.concurrent.TimeoutException) return true;
            cause = cause.getCause();
        }
        return false;
    }

    private LlmCallException mapHttpError(String agentName, WebClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 429) return LlmCallException.rateLimited(agentName);
        if (status >= 500) return LlmCallException.serverError(agentName, status);
        return new LlmCallException(
                "HTTP %d from NousRestClient for agent [%s]: %s".formatted(status, agentName, e.getMessage()),
                status, false);
    }
}
