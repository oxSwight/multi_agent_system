package com.midas.d3.api;

import com.midas.d3.security.JwtService;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration tests for the denial-of-wallet rate limiter ({@link com.midas.d3.ratelimit.RateLimitInterceptor}).
 *
 * <p>Rate limiting is disabled globally in {@code application.yml} (test) so the rest of the IT suite is
 * not throttled; this class re-enables it with a tiny run-creation capacity via {@link TestPropertySource},
 * which also forces its own application context (fresh buckets). Each test uses a distinct JWT {@code chatId}
 * so its per-caller bucket is independent of the others sharing the same context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "midas.ratelimit.enabled=true",
        "midas.ratelimit.run-creation.capacity=2",
        "midas.ratelimit.run-creation.period=1m",
        "midas.ratelimit.general.capacity=1000",
        "midas.ratelimit.general.period=1m"
})
@DisplayName("Rate limiting — denial-of-wallet IT")
class RateLimitIT {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtService jwtService;

    private final List<String> startedRuns = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        RestAssured.basePath = "/api/v1/pipelines";
    }

    @AfterEach
    void tearDown() {
        // Best-effort cleanup of any runs created during the test (DELETE uses the looser general tier).
        for (String runId : startedRuns) {
            try {
                given(specFor(9_000L)).delete("/" + runId);
            } catch (Exception ignored) {
                // teardown must never mask a real assertion failure
            }
        }
        RestAssured.reset();
    }

    @Test
    @DisplayName("run-creation over capacity → 429 with Retry-After and the standard error envelope")
    void runCreation_overCapacity_returns429() {
        RequestSpecification caller = specFor(7_001L);

        // capacity = 2 → the first two creations are accepted…
        for (int i = 0; i < 2; i++) {
            startedRuns.add(given(caller)
                    .contentType(ContentType.JSON)
                    .body(Map.of("rawUserIdea", "rate-limit run " + i))
                    .when().post()
                    .then().statusCode(201)
                    .extract().path("runId"));
        }

        // …and the third is throttled.
        given(caller)
                .contentType(ContentType.JSON)
                .body(Map.of("rawUserIdea", "one run too many"))
            .when().post()
            .then()
                .statusCode(429)
                .header(HttpHeaders.RETRY_AFTER, notNullValue())
                .body("status", equalTo(429))
                .body("error", equalTo("Too Many Requests"))
                .body("message", notNullValue());
    }

    @Test
    @DisplayName("Per-caller isolation: one caller being throttled does not block a different caller")
    void runCreation_perCallerIsolation() {
        RequestSpecification throttled = specFor(7_002L);
        for (int i = 0; i < 2; i++) {
            startedRuns.add(given(throttled)
                    .contentType(ContentType.JSON)
                    .body(Map.of("rawUserIdea", "isolation run " + i))
                    .when().post()
                    .then().statusCode(201)
                    .extract().path("runId"));
        }
        given(throttled)
                .contentType(ContentType.JSON)
                .body(Map.of("rawUserIdea", "throttled"))
            .when().post()
            .then().statusCode(429);

        // A different caller has its own bucket and is unaffected.
        startedRuns.add(given(specFor(7_003L))
                .contentType(ContentType.JSON)
                .body(Map.of("rawUserIdea", "fresh caller"))
            .when().post()
            .then().statusCode(201)
                .extract().path("runId"));
    }

    @Test
    @DisplayName("Read endpoints use the looser general tier and are not blocked by the run-creation cap")
    void reads_notBlockedByRunCreationCap() {
        RequestSpecification caller = specFor(7_004L);
        // Exhaust the tight run-creation bucket.
        for (int i = 0; i < 2; i++) {
            startedRuns.add(given(caller)
                    .contentType(ContentType.JSON)
                    .body(Map.of("rawUserIdea", "reads run " + i))
                    .when().post()
                    .then().statusCode(201)
                    .extract().path("runId"));
        }
        given(caller).contentType(ContentType.JSON).body(Map.of("rawUserIdea", "blocked"))
                .when().post().then().statusCode(429);

        // GET /count is on the general tier (capacity 1000) → still served.
        given(caller).when().get("/count").then().statusCode(200);
    }

    @Test
    @DisplayName("Percent-encoded run-creation route is still metered on the tight tier (no bypass)")
    void runCreation_encodedPath_stillMeteredOnRunCreationTier() {
        RequestSpecification caller = specFor(7_005L);
        // Exhaust the run-creation bucket (capacity=2) via the canonical route.
        for (int i = 0; i < 2; i++) {
            startedRuns.add(given(caller)
                    .contentType(ContentType.JSON)
                    .body(Map.of("rawUserIdea", "encoded-guard run " + i))
                    .when().post()
                    .then().statusCode(201)
                    .extract().path("runId"));
        }
        // "/star%74" decodes to "/start" and Spring routes it to the same expensive handler; because the
        // tier is keyed off the resolved handler (not the raw URI), it is caught by the run-creation tier
        // (429) rather than slipping into the looser general tier.
        given(caller)
                .urlEncodingEnabled(false)
                .contentType(ContentType.JSON)
                .body(Map.of("rawUserIdea", "encoded bypass attempt"))
            .when().post("/star%74")
            .then().statusCode(429);
    }

    /** Builds a RestAssured spec carrying a valid Bearer token for {@code chatId}. */
    private RequestSpecification specFor(long chatId) {
        return new RequestSpecBuilder()
                .setPort(port)
                .setBasePath("/api/v1/pipelines")
                .addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateToken(chatId))
                .build();
    }
}
