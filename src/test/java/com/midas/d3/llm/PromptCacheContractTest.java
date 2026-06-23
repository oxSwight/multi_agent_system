package com.midas.d3.llm;

import com.midas.d3.llm.nous.dto.NousRequest;
import com.midas.d3.statemachine.MidasState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the prompt-cache contract (C1): the stable prefix (system prompt + base user message) must
 * stay byte-identical across retry attempts, with per-attempt correction feedback isolated to a
 * volatile suffix that lands at a clean message boundary. If a future change folds volatile data
 * back into the cacheable prefix, these tests fail — the cache invariant is no longer silent.
 */
@DisplayName("Prompt-cache contract")
class PromptCacheContractTest {

    private static final String SYSTEM = "You are the System Analyst. Output JSON.";
    private static final String BASE_USER = "USER IDEA:\nbuild a todo app\n\nUPSTREAM ARTIFACTS:\n## technicalSpec\n{...}";
    private static final String CORRECTION = "--- CORRECTION REQUIRED (attempt 2 of 3) ---\nFix the schema.";

    private LlmCallRequest baseRequest() {
        return LlmCallRequest.of(MidasState.SYSTEM_ANALYSIS, "SystemAnalystAgent", SYSTEM, BASE_USER, "run-cache-1");
    }

    @Nested
    @DisplayName("LlmCallRequest split")
    class RequestSplit {

        @Test
        @DisplayName("base request: whole user message is the cacheable prefix, no volatile suffix")
        void baseRequestHasNoSuffix() {
            LlmCallRequest req = baseRequest();
            assertThat(req.getCacheableUserPrefix()).isEqualTo(BASE_USER);
            assertThat(req.getUserMessage()).isEqualTo(BASE_USER);
            assertThat(req.hasVolatileSuffix()).isFalse();
            assertThat(req.getVolatileSuffix()).isEmpty();
        }

        @Test
        @DisplayName("withCorrectionFeedback preserves the prefix verbatim and isolates the suffix")
        void correctionPreservesPrefix() {
            LlmCallRequest base = baseRequest();
            LlmCallRequest retry = base.withCorrectionFeedback(CORRECTION);

            // The cache key (system + prefix) is unchanged across attempts.
            assertThat(retry.getSystemPrompt()).isEqualTo(base.getSystemPrompt());
            assertThat(retry.getCacheableUserPrefix()).isEqualTo(base.getCacheableUserPrefix());
            // The correction is carried as the volatile suffix and appended into the full message.
            assertThat(retry.hasVolatileSuffix()).isTrue();
            assertThat(retry.getVolatileSuffix()).isEqualTo(CORRECTION);
            assertThat(retry.getUserMessage()).isEqualTo(BASE_USER + "\n\n" + CORRECTION);
            // The base request is immutable — the wither returns a new instance.
            assertThat(base.hasVolatileSuffix()).isFalse();
        }

        @Test
        @DisplayName("a blank correction yields no volatile suffix (prefix == full message)")
        void blankCorrectionIsNoOp() {
            LlmCallRequest retry = baseRequest().withCorrectionFeedback("   ");
            assertThat(retry.hasVolatileSuffix()).isFalse();
            assertThat(retry.getUserMessage()).isEqualTo(BASE_USER);
        }

        @Test
        @DisplayName("each retry re-grafts onto the same base — corrections never accumulate")
        void correctionsDoNotAccumulate() {
            LlmCallRequest base = baseRequest();
            String c3 = "--- CORRECTION REQUIRED (attempt 3 of 3) ---\nStill wrong.";
            assertThat(base.withCorrectionFeedback(c3).getCacheableUserPrefix())
                    .isEqualTo(BASE_USER)
                    .doesNotContain("CORRECTION REQUIRED");
        }
    }

    @Nested
    @DisplayName("NousRequest message boundaries")
    class NousMessages {

        @Test
        @DisplayName("no correction → exactly [system, user(prefix)]")
        void twoMessagesWhenNoCorrection() {
            NousRequest req = NousRequest.of("qwen2.5-coder:14b", SYSTEM, BASE_USER, "");
            assertThat(req.getMessages()).hasSize(2);
            assertThat(req.getMessages().get(0).getRole()).isEqualTo("system");
            assertThat(req.getMessages().get(0).getContent()).isEqualTo(SYSTEM);
            assertThat(req.getMessages().get(1).getRole()).isEqualTo("user");
            assertThat(req.getMessages().get(1).getContent()).isEqualTo(BASE_USER);
        }

        @Test
        @DisplayName("correction → [system, user(prefix), user(correction)] with the prefix unchanged")
        void threeMessagesWhenCorrection() {
            NousRequest withCorr = NousRequest.of("qwen2.5-coder:14b", SYSTEM, BASE_USER, CORRECTION);
            assertThat(withCorr.getMessages()).hasSize(3);
            // First two messages are byte-identical to the no-correction case → clean cache boundary.
            assertThat(withCorr.getMessages().get(0).getContent()).isEqualTo(SYSTEM);
            assertThat(withCorr.getMessages().get(1).getContent()).isEqualTo(BASE_USER);
            assertThat(withCorr.getMessages().get(2).getRole()).isEqualTo("user");
            assertThat(withCorr.getMessages().get(2).getContent()).isEqualTo(CORRECTION);
        }

        @Test
        @DisplayName("legacy 3-arg factory still produces the two-message shape")
        void legacyFactoryUnchanged() {
            NousRequest req = NousRequest.of("qwen2.5-coder:14b", SYSTEM, BASE_USER);
            assertThat(req.getMessages()).hasSize(2);
        }
    }
}
