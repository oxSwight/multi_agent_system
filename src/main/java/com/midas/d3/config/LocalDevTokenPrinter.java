package com.midas.d3.config;

import com.midas.d3.security.JwtService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev-only convenience bean. When the {@code local-dev} profile is active, it mints a 24-hour JWT
 * for a fixed dev principal at startup and prints it to the console so a developer can paste it
 * straight into an {@code Authorization: Bearer <token>} header.
 *
 * <p><b>Strictly local.</b> {@link Profile @Profile("local-dev")} guarantees this never loads in any
 * other profile (prod/staging/default), so no auto-token is ever emitted outside local development.
 * It signs with the regular {@link JwtService}, i.e. the same key the running app validates against,
 * so the printed token is immediately usable against the protected pipeline API.
 */
@Slf4j
@Component
@Profile("local-dev")
@RequiredArgsConstructor
public class LocalDevTokenPrinter {

    /** Arbitrary dev principal embedded as the token's {@code chatId} claim. */
    private static final long DEV_CHAT_ID = 1L;

    private final JwtService jwtService;

    @PostConstruct
    void printDevToken() {
        String token = jwtService.generateToken(DEV_CHAT_ID);
        log.info("""

                ================== LOCAL-DEV JWT (valid 24h, chatId={}) ==================
                Send it as header:  Authorization: Bearer <token>
                ------------------------------------------------------------------------
                {}
                ========================================================================
                """, DEV_CHAT_ID, token);
    }
}
