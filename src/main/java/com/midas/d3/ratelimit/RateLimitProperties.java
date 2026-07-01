package com.midas.d3.ratelimit;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Typed configuration for per-caller API rate limiting (denial-of-wallet defence).
 *
 * <p>Each authenticated caller gets an in-memory token bucket per tier. The {@link #runCreation}
 * tier is a tight cap on the expensive {@code POST /api/v1/pipelines} endpoint — every accepted
 * request there kicks off a full multi-agent LLM pipeline, so an unthrottled caller could burn an
 * unbounded amount of generation budget. The {@link #general} tier is a looser cap on all other
 * {@code /api/**} calls (status/context/artifact reads, deletes) to blunt API hammering.
 *
 * <p>Buckets live in process memory (single-instance deployment, see the roadmap's single-instance
 * constraint); a horizontally-scaled deployment would swap the store for a shared backend.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "midas.ratelimit")
public class RateLimitProperties {

    /** Master switch. When {@code false} the interceptor is a pass-through (used to disable in tests). */
    private boolean enabled = true;

    /** Tight cap on run creation ({@code POST /api/v1/pipelines[/start]}) — the denial-of-wallet guard. */
    private Tier runCreation = new Tier(10, Duration.ofMinutes(1));

    /** Looser cap on every other {@code /api/**} request from the same caller. */
    private Tier general = new Tier(120, Duration.ofMinutes(1));

    /** A single token-bucket tier: {@code capacity} tokens that greedily refill over {@code period}. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Tier {
        /** Bucket size — also the burst allowance. */
        private long capacity;
        /** Time for the bucket to refill from empty to full. */
        private Duration period;
    }
}
