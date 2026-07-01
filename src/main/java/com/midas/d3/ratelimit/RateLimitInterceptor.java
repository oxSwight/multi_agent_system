package com.midas.d3.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-caller token-bucket rate limiter (bucket4j) applied to {@code /api/**}.
 *
 * <p>Runs as a Spring MVC interceptor, i.e. <em>after</em> the security filter chain — so for the
 * authenticated pipeline/dashboard API the caller principal (the JWT {@code chatId}) is always
 * present and is used as the bucket key. Expensive run creation is metered on its own tight tier
 * ({@link RateLimitProperties#getRunCreation()}); everything else shares a looser tier
 * ({@link RateLimitProperties#getGeneral()}). Over-limit requests get {@code 429 Too Many Requests}
 * with a {@code Retry-After} header and the standard JSON error envelope.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitProperties props;
    private final ObjectMapper objectMapper;

    /** One bucket per {@code tier:caller}. In-memory, single-instance (see {@link RateLimitProperties}). */
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Skip when disabled and never meter CORS preflight (cheap, and blocking it breaks the browser).
        if (!props.isEnabled() || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        boolean runCreation = isRunCreation(handler);
        RateLimitProperties.Tier tier = runCreation ? props.getRunCreation() : props.getGeneral();
        String tierName = runCreation ? "run" : "api";
        String caller = resolveCaller(request);

        Bucket bucket = buckets.computeIfAbsent(tierName + ':' + caller, k -> newBucket(tier));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", Long.toString(probe.getRemainingTokens()));
            return true;
        }

        long retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
        writeTooManyRequests(response, retryAfterSeconds, runCreation);
        log.warn("[RateLimit] 429 for caller [{}] on [{} {}] (tier={}, retryAfter={}s).",
                caller, request.getMethod(), request.getRequestURI(), tierName, retryAfterSeconds);
        return false;
    }

    private Bucket newBucket(RateLimitProperties.Tier tier) {
        // capacity tokens that greedily refill over the period (a smooth "N per period" limiter).
        Bandwidth limit = Bandwidth.builder()
                .capacity(tier.getCapacity())
                .refillGreedy(tier.getCapacity(), tier.getPeriod())
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * Run creation is metered off the RESOLVED handler carrying {@link RunCreationRateLimit}, not the
     * request path string. Spring routes on the decoded path, so keying on the handler makes the tight
     * cap immune to percent-encoded / alternate-form URLs (e.g. {@code /pipelines/star%74}) that would
     * otherwise route to the expensive endpoint while dodging a literal path comparison.
     */
    private boolean isRunCreation(Object handler) {
        return handler instanceof HandlerMethod hm && hm.hasMethodAnnotation(RunCreationRateLimit.class);
    }

    /** Prefer the authenticated principal (JWT chatId); fall back to client IP for any public path. */
    private String resolveCaller(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getName() != null && !"anonymousUser".equals(auth.getName())) {
            return "u:" + auth.getName();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
        return "ip:" + (ip != null ? ip : "unknown");
    }

    private void writeTooManyRequests(HttpServletResponse response, long retryAfterSeconds, boolean runCreation) {
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String message = runCreation
                ? "Rate limit exceeded for pipeline creation — slow down to avoid runaway generation cost."
                : "Rate limit exceeded — too many requests.";
        try {
            Map<String, Object> body = Map.of(
                    "status", 429,
                    "error", "Too Many Requests",
                    "message", message,
                    "timestamp", Instant.now().toString());
            response.getWriter().write(objectMapper.writeValueAsString(body));
        } catch (Exception ignored) {
            // The 429 status + Retry-After are already set — a body-serialisation failure is non-fatal.
        }
    }
}
