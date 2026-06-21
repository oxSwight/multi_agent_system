package com.midas.d3.llm.nous;

import com.midas.d3.llm.LlmCallException;
import com.midas.d3.llm.LlmCallRequest;
import com.midas.d3.llm.LlmCallResult;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.llm.nous.dto.NousRequest;
import com.midas.d3.llm.nous.dto.NousResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "midas.llm", name = "client-type", havingValue = "NOUS")
public class NousRestClient implements LlmClient {

    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final int MAX_RATE_LIMIT_RETRIES = 3;
    private static final long RATE_LIMIT_RETRY_DELAY_MS = 1_000L;

    private final WebClient       webClient;
    private final NousProperties  properties;
    private final int             timeoutSeconds;
    private final int             httpMaxRetries;

    public NousRestClient(WebClient.Builder webClientBuilder, NousProperties properties) {
        this.properties     = properties;
        this.timeoutSeconds = properties.getTimeoutSeconds();
        this.httpMaxRetries = Math.max(0, properties.getHttpMaxRetries());

        String baseUrl = properties.getBaseUrl();
        String apiKey  = properties.getApiKey();

        WebClient.Builder builder = webClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT,       MediaType.APPLICATION_JSON_VALUE);

        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }

        this.webClient = builder.build();
        NousProperties.Routing routing = properties.getRouting();
        if (routing.isEnabled()) {
            log.info("[NousRestClient] Initialized: baseUrl={}, routing=enabled, defaultModel={}, "
                            + "agentMappings={}, timeoutSeconds={}, maxRetries={}",
                    baseUrl, routing.getDefaultModel(), routing.getAgents().size(),
                    timeoutSeconds, this.httpMaxRetries);
        } else {
            log.info("[NousRestClient] Initialized: baseUrl={}, model={}, timeoutSeconds={}, maxRetries={}",
                    baseUrl, properties.getModel(), timeoutSeconds, this.httpMaxRetries);
        }
    }

    @Override
    public LlmCallResult call(LlmCallRequest request) throws LlmCallException {
        String effectiveModel = resolveModel(request);
        NousRequest body = NousRequest.of(effectiveModel, request.getSystemPrompt(), request.getUserMessage());

        log.info("[NousRestClient] Calling agent=[{}] run=[{}] model={}",
                request.getAgentName(), request.getPipelineRunId(), effectiveModel);

        int serverErrorAttempts = 0;
        int rateLimitAttempts = 0;

        while (true) {
            try {
                NousResponse response = webClient.post()
                        .uri(CHAT_COMPLETIONS_PATH)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(NousResponse.class)
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .block();

                if (response == null) {
                    throw LlmCallException.emptyResponse(request.getAgentName());
                }

                String finishReason = response.extractFinishReason().orElse("");
                String text = response.extractText()
                        .orElseThrow(() -> LlmCallException.emptyResponse(request.getAgentName()));

                if (LlmCallResult.FINISH_REASON_MAX_TOKENS.equals(finishReason)) {
                    log.warn("[NousRestClient][{}] run=[{}] — ответ обрезан (MAX_TOKENS), "
                                    + "retry бесполезен без уменьшения scope",
                            request.getAgentName(), request.getPipelineRunId());
                }

                return LlmCallResult.of(text, effectiveModel, 0, 0, finishReason);

            } catch (LlmCallException e) {
                throw e;
            } catch (WebClientResponseException e) {
                int status = e.getStatusCode().value();

                if (status == 429) {
                    if (rateLimitAttempts >= MAX_RATE_LIMIT_RETRIES) {
                        log.error("[NousRestClient][{}] HTTP 429 rate limit exhausted after {} retries.",
                                request.getAgentName(), MAX_RATE_LIMIT_RETRIES);
                        throw LlmCallException.rateLimitExhausted("Nous", request.getAgentName());
                    }
                    rateLimitAttempts++;
                    log.warn("[NousRestClient][{}] HTTP 429 rate limit — rapid retry {}/{}.",
                            request.getAgentName(), rateLimitAttempts, MAX_RATE_LIMIT_RETRIES);
                    sleepQuietly(RATE_LIMIT_RETRY_DELAY_MS);
                    continue;
                }

                if (status >= 500 && serverErrorAttempts < httpMaxRetries) {
                    serverErrorAttempts++;
                    log.warn("[NousRestClient][{}] HTTP {} — server-error retry {}/{}",
                            request.getAgentName(), status, serverErrorAttempts, httpMaxRetries);
                    sleepQuietly(5_000L);
                    continue;
                }

                log.error("[NousRestClient][{}] HTTP {} — {}",
                        request.getAgentName(), e.getStatusCode(), e.getResponseBodyAsString());
                throw mapHttpError(request.getAgentName(), e);

            } catch (Exception e) {
                WebClientResponseException httpCause = findWebClientCause(e);
                if (httpCause != null) {
                    int status = httpCause.getStatusCode().value();

                    if (status == 429) {
                        if (rateLimitAttempts >= MAX_RATE_LIMIT_RETRIES) {
                            log.error("[NousRestClient][{}] HTTP 429 (wrapped) rate limit exhausted after {} retries.",
                                    request.getAgentName(), MAX_RATE_LIMIT_RETRIES);
                            throw LlmCallException.rateLimitExhausted("Nous", request.getAgentName());
                        }
                        rateLimitAttempts++;
                        log.warn("[NousRestClient][{}] HTTP 429 (wrapped) — rapid retry {}/{}.",
                                request.getAgentName(), rateLimitAttempts, MAX_RATE_LIMIT_RETRIES);
                        sleepQuietly(RATE_LIMIT_RETRY_DELAY_MS);
                        continue;
                    }

                    if (status >= 500 && serverErrorAttempts < httpMaxRetries) {
                        serverErrorAttempts++;
                        log.warn("[NousRestClient][{}] HTTP {} (wrapped) — server-error retry {}/{}",
                                request.getAgentName(), status, serverErrorAttempts, httpMaxRetries);
                        sleepQuietly(5_000L);
                        continue;
                    }

                    log.error("[NousRestClient][{}] HTTP {} after retries — {}",
                            request.getAgentName(), httpCause.getStatusCode(),
                            httpCause.getResponseBodyAsString());
                    throw mapHttpError(request.getAgentName(), httpCause);
                }

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
    }

    @Override
    public String defaultModelId() {
        NousProperties.Routing routing = properties.getRouting();
        if (routing.isEnabled() && routing.getDefaultModel() != null && !routing.getDefaultModel().isBlank()) {
            return routing.getDefaultModel().trim();
        }
        return properties.getModel();
    }

    private String resolveModel(LlmCallRequest request) {
        NousProperties.Routing routing = properties.getRouting();
        if (routing.isEnabled()) {
            String agentName = request.getAgentName();
            String mapped = routing.getAgents().get(agentName);
            if (mapped != null && !mapped.isBlank()) {
                return mapped.trim();
            }
            if (routing.getDefaultModel() != null && !routing.getDefaultModel().isBlank()) {
                return routing.getDefaultModel().trim();
            }
            return properties.getModel();
        }

        String override = request.getModelOverride();
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        return properties.getModel();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LlmCallException("Interrupted while waiting for Nous rate-limit reset", ie, true);
        }
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
        if (status == 429) return LlmCallException.rateLimitExhausted("Nous", agentName);
        if (status >= 500) return LlmCallException.serverError(agentName, status);
        return new LlmCallException(
                "HTTP %d from NousRestClient for agent [%s]: %s".formatted(status, agentName, e.getMessage()),
                status, false);
    }
}
