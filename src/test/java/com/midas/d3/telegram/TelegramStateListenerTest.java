package com.midas.d3.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.context.MidasContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TelegramStateListener Tests")
class TelegramStateListenerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("renderFinalCompletion with delivered document surfaces PASS verdict")
    void renderFinalCompletion_documentDelivered_includesPassVerdict() throws Exception {
        MidasContext ctx = MidasContext.start("Build a CRM", "run-pass-001")
                .withProductReviewReport(MAPPER.readTree("""
                        {"verdict":"PASS","summary":"ok","remediation_block":{"recommendations":[]}}
                        """));

        String message = TelegramStateListener.renderFinalCompletion(ctx, true);

        assertThat(message).contains("Архив артефактов доставлен");
        assertThat(message).contains("Контроль качества: <b>PASS</b>");
        assertThat(message).contains("/artifacts");
    }

    @Test
    @DisplayName("renderFinalCompletion surfaces PASS_WITH_NOTES verdict")
    void renderFinalCompletion_passWithNotes_includesVerdict() throws Exception {
        MidasContext ctx = MidasContext.start("Build a CRM", "run-notes-001")
                .withProductReviewReport(MAPPER.readTree("""
                        {"verdict":"PASS_WITH_NOTES","summary":"minor gaps"}
                        """));

        String message = TelegramStateListener.renderFinalCompletion(ctx, true);

        assertThat(message).contains("Контроль качества: <b>PASS_WITH_NOTES</b>");
    }

    @Test
    @DisplayName("renderFinalCompletion omits REJECT verdict from final message")
    void renderFinalCompletion_rejectVerdict_omitted() throws Exception {
        MidasContext ctx = MidasContext.start("Build a CRM", "run-reject-001")
                .withProductReviewReport(MAPPER.readTree("""
                        {"verdict":"REJECT","summary":"missing features"}
                        """));

        String message = TelegramStateListener.renderFinalCompletion(ctx, false);

        assertThat(message).doesNotContain("Контроль качества");
    }

    @Test
    @DisplayName("extractProductReviewVerdict returns PASS for passing report")
    void extractProductReviewVerdict_passReport_returnsPass() throws Exception {
        MidasContext ctx = MidasContext.start("idea", "run-001")
                .withProductReviewReport(MAPPER.readTree("{\"verdict\":\"PASS\"}"));

        assertThat(TelegramStateListener.extractProductReviewVerdict(ctx)).isEqualTo("PASS");
    }
}
