package com.midas.d3.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller handler method as an expensive "run creation" operation that must be metered on
 * the tight run-creation rate-limit tier (the denial-of-wallet cap) rather than the looser general tier.
 *
 * <p>{@link RateLimitInterceptor} selects the tier from this annotation on the <em>resolved</em>
 * {@link org.springframework.web.method.HandlerMethod} — not from the request path string — so
 * percent-encoded or alternate-form URLs that Spring still routes to the handler cannot slip into the
 * cheaper tier and bypass the cap.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RunCreationRateLimit {
}
