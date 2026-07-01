package com.midas.d3.llm;

import com.midas.d3.llm.dto.GeminiRequest;
import com.midas.d3.llm.dto.GeminiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Gemini API implementation of {@link LlmClient}.
 *
 * <p><b>Endpoint:</b>
 * {@code POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={apiKey}}
 *
 * <p><b>Retry policy:</b>
 * <ul>
 *   <li>HTTP 429 — parsed wait from {@code Retry-After} header or error body; fallback 45 s; retried internally</li>
 *   <li>HTTP 5xx — up to {@code midas.llm.http-max-retries} attempts with 5-second back-off</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> {@link WebClient} is inherently thread-safe. This bean
 * is safe for concurrent use.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "midas.llm", name = "client-type", havingValue = "GEMINI", matchIfMissing = true)
public class GeminiLlmClient implements LlmClient {

    private static final String GEMINI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    private static final int MAX_RATE_LIMIT_RETRIES = 3;
    private static final long RATE_LIMIT_RETRY_DELAY_MS = 1_000L;

    /** Default pause when Gemini does not specify a retry interval (free-tier quota reset). */
    private static final long DEFAULT_RATE_LIMIT_WAIT_MS = 45_000L;

    private static final Pattern RETRY_IN_SECONDS = Pattern.compile(
            "(?i)retry\\s+(?:in|after)\\s+~?(\\d+(?:\\.\\d+)?)\\s*s(?:ec(?:ond)?s?)?");
    private static final Pattern RETRY_AFTER_SECONDS = Pattern.compile(
            "(?i)retry\\s+after\\s+(\\d+(?:\\.\\d+)?)\\s*(?:s(?:ec(?:ond)?s?)?)?");

    private final WebClient webClient;
    private final String    apiKey;
    private final String    model;
    private final int       timeoutSeconds;
    private final int       httpMaxRetries;
    private final int       maxResponseMb;

    public GeminiLlmClient(
            WebClient.Builder webClientBuilder,
            @Value("${midas.llm.api-key:}") String apiKey,
            @Value("${midas.llm.model:gemini-2.5-flash}") String model,
            @Value("${midas.llm.timeout-seconds:120}") int timeoutSeconds,
            @Value("${midas.llm.http-max-retries:2}") int httpMaxRetries,
            @Value("${midas.llm.max-response-mb:16}") int maxResponseMb) {

        this.apiKey         = apiKey;
        this.model          = model;
        this.timeoutSeconds = timeoutSeconds;
        this.httpMaxRetries = Math.max(0, httpMaxRetries);
        this.maxResponseMb  = Math.max(1, maxResponseMb);

        this.webClient = webClientBuilder
                .baseUrl(GEMINI_BASE_URL)
                // Raise the 256KB WebFlux codec default so large generations don't crash the core step
                // (also classified non-retryable below to fail fast rather than burn the retry budget).
                .codecs(c -> c.defaultCodecs().maxInMemorySize(this.maxResponseMb * 1024 * 1024))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // ── LlmClient ─────────────────────────────────────────────────────────────

    @Override
    public LlmCallResult call(LlmCallRequest request) throws LlmCallException {
        validateApiKey(request.getAgentName());

        String effectiveModel = resolveModel(request);
        GeminiRequest body = GeminiRequest.of(request.getSystemPrompt(), request.getUserMessage());
        String url = effectiveModel + ":generateContent";

        log.info("[GeminiLlmClient] Calling [{}] for run [{}]  model={}",
                request.getAgentName(), request.getPipelineRunId(), effectiveModel);

        int serverErrorAttempts = 0;
        int rateLimitAttempts = 0;

        while (true) {
            try {
                GeminiResponse response = webClient.post()
                        .uri(uriBuilder -> uriBuilder
                                .path(url)
                                .queryParam("key", apiKey)
                                .build())
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(GeminiResponse.class)
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .block();

                if (response == null) {
                    throw LlmCallException.emptyResponse(request.getAgentName());
                }

                String finishReason = response.extractFinishReason().orElse("");
                String text = response.extractText()
                        .orElseThrow(() -> LlmCallException.emptyResponse(request.getAgentName()));

                if (LlmCallResult.FINISH_REASON_MAX_TOKENS.equals(finishReason)) {
                    log.warn("[GeminiLlmClient][{}] run=[{}] — ответ обрезан (MAX_TOKENS), "
                                    + "retry бесполезен без уменьшения scope",
                            request.getAgentName(), request.getPipelineRunId());
                }

                return LlmCallResult.of(
                        text,
                        effectiveModel,
                        response.extractPromptTokens(),
                        response.extractCompletionTokens(),
                        finishReason);

            } catch (LlmCallException e) {
                throw e;
            } catch (WebClientResponseException e) {
                int status = e.getStatusCode().value();

                if (status == 429) {
                    if (rateLimitAttempts >= MAX_RATE_LIMIT_RETRIES) {
                        log.error("[GeminiLlmClient][{}] HTTP 429 rate limit exhausted after {} retries. Body: {}",
                                request.getAgentName(), MAX_RATE_LIMIT_RETRIES,
                                truncate(e.getResponseBodyAsString(), 300));
                        throw LlmCallException.rateLimitExhausted("Gemini", request.getAgentName());
                    }
                    rateLimitAttempts++;
                    log.warn("[GeminiLlmClient][{}] HTTP 429 rate limit — rapid retry {}/{}. Body: {}",
                            request.getAgentName(), rateLimitAttempts, MAX_RATE_LIMIT_RETRIES,
                            truncate(e.getResponseBodyAsString(), 300));
                    sleepQuietly(RATE_LIMIT_RETRY_DELAY_MS);
                    continue;
                }

                if (status >= 500 && serverErrorAttempts < httpMaxRetries) {
                    serverErrorAttempts++;
                    log.warn("[GeminiLlmClient][{}] HTTP {} — server-error retry {}/{}",
                            request.getAgentName(), status, serverErrorAttempts, httpMaxRetries);
                    sleepQuietly(5_000L);
                    continue;
                }

                log.error("[GeminiLlmClient][{}] HTTP {} — {}",
                        request.getAgentName(), e.getStatusCode(), e.getResponseBodyAsString());
                throw mapHttpError(request.getAgentName(), e);

            } catch (Exception e) {
                WebClientResponseException httpCause = findWebClientCause(e);
                if (httpCause != null) {
                    int status = httpCause.getStatusCode().value();

                    if (status == 429) {
                        if (rateLimitAttempts >= MAX_RATE_LIMIT_RETRIES) {
                            log.error("[GeminiLlmClient][{}] HTTP 429 (wrapped) rate limit exhausted after {} retries.",
                                    request.getAgentName(), MAX_RATE_LIMIT_RETRIES);
                            throw LlmCallException.rateLimitExhausted("Gemini", request.getAgentName());
                        }
                        rateLimitAttempts++;
                        log.warn("[GeminiLlmClient][{}] HTTP 429 (wrapped) — rapid retry {}/{}.",
                                request.getAgentName(), rateLimitAttempts, MAX_RATE_LIMIT_RETRIES);
                        sleepQuietly(RATE_LIMIT_RETRY_DELAY_MS);
                        continue;
                    }

                    if (status >= 500 && serverErrorAttempts < httpMaxRetries) {
                        serverErrorAttempts++;
                        log.warn("[GeminiLlmClient][{}] HTTP {} (wrapped) — server-error retry {}/{}",
                                request.getAgentName(), status, serverErrorAttempts, httpMaxRetries);
                        sleepQuietly(5_000L);
                        continue;
                    }

                    log.error("[GeminiLlmClient][{}] HTTP {} after retries — {}",
                            request.getAgentName(), httpCause.getStatusCode(),
                            httpCause.getResponseBodyAsString());
                    throw mapHttpError(request.getAgentName(), httpCause);
                }

                if (LlmErrorClassifier.isBufferLimitError(e)) {
                    log.error("[GeminiLlmClient][{}] LLM response exceeded the {} MB in-memory buffer — "
                            + "failing fast (not retryable).", request.getAgentName(), maxResponseMb);
                    throw new LlmCallException(
                            "LLM response for agent [%s] exceeded the %d MB buffer limit "
                                    .formatted(request.getAgentName(), maxResponseMb)
                                    + "(midas.llm.max-response-mb). Not retryable — raise the limit or reduce scope.",
                            e, false);
                }

                if (isTimeoutCause(e)) {
                    log.error("[GeminiLlmClient][{}] Request timed out after {} s.",
                            request.getAgentName(), timeoutSeconds);
                    throw LlmCallException.timeout(request.getAgentName());
                }

                log.error("[GeminiLlmClient][{}] Unexpected error.", request.getAgentName(), e);
                throw new LlmCallException(
                        "Unexpected error calling LLM for agent [" + request.getAgentName() + "]: " + e.getMessage(),
                        e, true);
            }
        }
    }

    @Override
    public String defaultModelId() {
        return model;
    }

    private String resolveModel(LlmCallRequest request) {
        String override = request.getModelOverride();
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        return model;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void validateApiKey(String agentName) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmCallException(
                    "Gemini API key (MIDAS_LLM_API_KEY) is not configured for agent [" + agentName + "]",
                    -1, false);
        }
    }

    /**
     * Derives the wait interval for a 429 response.
     * Priority: {@code Retry-After} header → error body text → {@link #DEFAULT_RATE_LIMIT_WAIT_MS}.
     */
    static long parseRateLimitWaitMs(WebClientResponseException e) {
        long fromHeader = parseRetryAfterHeader(e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        if (fromHeader > 0) {
            return fromHeader;
        }

        String body = e.getResponseBodyAsString();
        if (body != null && !body.isBlank()) {
            long fromBody = parseRetryAfterBody(body);
            if (fromBody > 0) {
                return fromBody;
            }
        }

        return DEFAULT_RATE_LIMIT_WAIT_MS;
    }

    private static long parseRetryAfterHeader(String retryAfter) {
        if (retryAfter == null || retryAfter.isBlank()) {
            return -1;
        }

        String trimmed = retryAfter.trim();
        try {
            double seconds = Double.parseDouble(trimmed);
            return Math.max(1L, Math.round(seconds * 1_000));
        } catch (NumberFormatException ignored) {
            // HTTP-date form
        }

        try {
            ZonedDateTime retryAt = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
            long deltaMs = Duration.between(ZonedDateTime.now(), retryAt).toMillis();
            return Math.max(1_000L, deltaMs);
        } catch (DateTimeParseException ignored) {
            return -1;
        }
    }

    private static long parseRetryAfterBody(String body) {
        for (Pattern pattern : new Pattern[]{RETRY_IN_SECONDS, RETRY_AFTER_SECONDS}) {
            Matcher matcher = pattern.matcher(body);
            if (matcher.find()) {
                return Math.max(1_000L, Math.round(Double.parseDouble(matcher.group(1)) * 1_000));
            }
        }
        return -1;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LlmCallException("Interrupted while waiting for Gemini rate-limit reset", ie, true);
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }

    private boolean isTimeoutCause(Throwable t) {
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof java.util.concurrent.TimeoutException) return true;
            cause = cause.getCause();
        }
        return false;
    }

    private WebClientResponseException findWebClientCause(Throwable t) {
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof WebClientResponseException e) return e;
            cause = cause.getCause();
        }
        return null;
    }

    private LlmCallException mapHttpError(String agentName, WebClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 429) return LlmCallException.rateLimitExhausted("Gemini", agentName);
        if (status >= 500) return LlmCallException.serverError(agentName, status);
        return new LlmCallException(
                "HTTP %d from Gemini for agent [%s]: %s".formatted(status, agentName, e.getMessage()),
                status, false);
    }
}
