package com.midas.d3.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JwtService — secret validation & decoding")
class JwtServiceTest {

    /** The publicly-known default shipped in application.yml — must always be rejected in prod. */
    private static final String INSECURE_DEFAULT =
            "c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0";

    private JwtService service(String secret, String... activeProfiles) {
        MockEnvironment env = new MockEnvironment();
        if (activeProfiles.length > 0) {
            env.setActiveProfiles(activeProfiles);
        }
        JwtService svc = new JwtService(env);
        ReflectionTestUtils.setField(svc, "base64Secret", secret);
        return svc;
    }

    @Test
    @DisplayName("URL-safe Base64 secret is accepted and round-trips (the live crash-loop fix)")
    void urlSafeSecret_isAcceptedAndSigns() {
        // 48 URL-safe chars -> 36 bytes (>= 32). Invalid as STANDARD Base64 ('_'), so it exercises
        // the URL-safe fallback whose absence crashed the whole Spring context on startup.
        JwtService svc = service("_".repeat(48));

        assertThatCode(svc::validateSecretOnStartup).doesNotThrowAnyException();
        String token = svc.generateToken(987654321L);
        assertThat(svc.isValid(token)).isTrue();
        assertThat(svc.extractChatId(token)).isEqualTo(987654321L);
    }

    @Test
    @DisplayName("standard Base64 secret still works")
    void standardSecret_isAccepted() {
        // base64("abcdefghijklmnopqrstuvwxyz0123456789") -> 36 bytes, standard alphabet only.
        JwtService svc = service("YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXowMTIzNDU2Nzg5");

        assertThatCode(svc::validateSecretOnStartup).doesNotThrowAnyException();
        assertThat(svc.isValid(svc.generateToken(1L))).isTrue();
    }

    @Test
    @DisplayName("a non-Base64 / unusable secret is still rejected (fallback is not a rubber stamp)")
    void garbageSecret_isRejected() {
        // Whatever the decoder makes of this, it cannot yield a >=32-byte key, so startup must fail.
        JwtService svc = service("!!!!notbase64!!!!");

        assertThatThrownBy(svc::validateSecretOnStartup)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a too-short key is still rejected")
    void shortSecret_isRejected() {
        JwtService svc = service("____"); // URL-safe, decodes to 3 bytes

        assertThatThrownBy(svc::validateSecretOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too weak");
    }

    @Test
    @DisplayName("the insecure shipped default is still rejected in a production-like profile")
    void insecureDefault_isRejected() {
        JwtService svc = service(INSECURE_DEFAULT);

        assertThatThrownBy(svc::validateSecretOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("insecure");
    }

    @Test
    @DisplayName("dev profile skips strict validation entirely")
    void devProfile_skipsValidation() {
        JwtService svc = service("anything-goes-locally", "dev");

        assertThatCode(svc::validateSecretOnStartup).doesNotThrowAnyException();
    }
}
