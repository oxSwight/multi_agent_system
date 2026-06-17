package com.midas.d3.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * Stateless JWT utility service for the MIDAS dashboard Magic Link flow.
 *
 * <h2>Token structure</h2>
 * <pre>
 *   Header : {"alg":"HS256","typ":"JWT"}
 *   Payload: {"chatId": <telegramChatId>, "iat": <epoch>, "exp": <iat + 24h>}
 * </pre>
 *
 * <h2>Key configuration</h2>
 * The signing key is read from {@code midas.security.jwt.secret} as a
 * Base64-encoded byte sequence (≥ 256 bits / 32 bytes for HS256).
 * Override via {@code MIDAS_JWT_SECRET} environment variable in production.
 */
@Slf4j
@Service
public class JwtService {

    private static final long   TOKEN_VALIDITY_MS = Duration.ofHours(24).toMillis();
    private static final String CLAIM_CHAT_ID     = "chatId";

    /**
     * Minimum signing-key length for HMAC-SHA256, per RFC 7518 §3.2: the key must be
     * at least as long as the hash output (256 bits = 32 bytes). Anything shorter is
     * cryptographically weak and rejected outright in production.
     */
    private static final int MIN_KEY_BYTES = 32;

    /**
     * The well-known development/CI default secret shipped in {@code application.yml}.
     * It is the Base64 string {@code "secret"} repeated eight times and is publicly
     * visible in source control — it must never be used to sign real tokens.
     */
    private static final String INSECURE_DEFAULT_SECRET =
            "c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0";

    /**
     * Profiles under which the insecure default secret is tolerated. Any other profile
     * — including {@code prod}, {@code staging}, or an empty/unset profile — is treated
     * as production-like and subjected to strict validation, so a deployment that simply
     * forgets to set its profile still fails fast rather than running with a known key.
     */
    private static final Set<String> TRUSTED_LOCAL_PROFILES = Set.of("dev", "test");

    private final Environment environment;

    @Value("${midas.security.jwt.secret}")
    private String base64Secret;

    public JwtService(Environment environment) {
        this.environment = environment;
    }

    // ── Startup validation ────────────────────────────────────────────────────

    /**
     * Fails the application startup when the configured JWT secret is unsafe for a
     * production-like environment. Runs once, after dependency injection, so a
     * misconfigured deployment refuses to start instead of silently signing tokens
     * with a weak or publicly-known key.
     *
     * <p>Validation is skipped only when a trusted local profile ({@code dev}/{@code test})
     * is active, allowing the mock secret to be used for local development and the test
     * suite. In every other case the secret must be present, valid Base64, at least
     * {@value #MIN_KEY_BYTES} bytes once decoded, and different from the shipped default.
     *
     * @throws IllegalStateException if the secret is missing, malformed, too short, or
     *                               equal to the insecure default in a production-like profile
     */
    @PostConstruct
    void validateSecretOnStartup() {
        if (isTrustedLocalProfileActive()) {
            log.info("[JwtService] Trusted local profile active — skipping strict JWT secret validation.");
            return;
        }

        String secret = base64Secret == null ? "" : base64Secret.trim();

        if (secret.isEmpty()) {
            throw new IllegalStateException(
                    "midas.security.jwt.secret is not configured. Set the MIDAS_JWT_SECRET "
                    + "environment variable to a Base64-encoded key of at least " + MIN_KEY_BYTES
                    + " bytes (256 bits) before starting in a production profile.");
        }

        if (INSECURE_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "midas.security.jwt.secret is still set to the insecure built-in default. "
                    + "This key is public in source control. Set MIDAS_JWT_SECRET to a unique, "
                    + "randomly-generated Base64 key of at least " + MIN_KEY_BYTES + " bytes.");
        }

        byte[] decoded;
        try {
            decoded = Decoders.BASE64.decode(secret);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "midas.security.jwt.secret is not valid Base64. Provide a Base64-encoded key "
                    + "of at least " + MIN_KEY_BYTES + " bytes via MIDAS_JWT_SECRET.", e);
        }

        if (decoded.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "midas.security.jwt.secret is too weak for HMAC-SHA256: decoded length is "
                    + decoded.length + " bytes but at least " + MIN_KEY_BYTES + " bytes (256 bits) "
                    + "are required. Generate a stronger key for MIDAS_JWT_SECRET.");
        }

        log.info("[JwtService] JWT signing secret validated for production-like profile(s): {}.",
                Arrays.toString(environment.getActiveProfiles()));
    }

    private boolean isTrustedLocalProfileActive() {
        return Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .anyMatch(TRUSTED_LOCAL_PROFILES::contains);
    }

    // ── Token generation ─────────────────────────────────────────────────────

    /**
     * Generates a signed JWT for the given Telegram {@code chatId}.
     * The token is valid for 24 hours from the moment of issuance.
     *
     * @param chatId Telegram chat ID to embed as a claim
     * @return compact JWT string
     */
    public String generateToken(long chatId) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + TOKEN_VALIDITY_MS);

        return Jwts.builder()
                .claim(CLAIM_CHAT_ID, chatId)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey())
                .compact();
    }

    // ── Token validation ─────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the token is structurally valid, signed with the
     * correct key, and has not expired.
     *
     * @param token compact JWT string
     * @return {@code true} when valid; {@code false} on any error
     */
    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("[JwtService] Token validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Extracts the {@code chatId} claim from a validated JWT.
     *
     * @param token compact JWT string (must be valid)
     * @return Telegram chat ID embedded in the token
     * @throws JwtException if the token is invalid or expired
     */
    public Long extractChatId(String token) {
        Claims claims = parseClaims(token);
        Object raw = claims.get(CLAIM_CHAT_ID);
        if (raw instanceof Number n) {
            return n.longValue();
        }
        throw new JwtException("Missing or invalid chatId claim in JWT");
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
    }
}
