package com.midas.d3.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.MidasState;
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

    @Test
    @DisplayName("renderRemediationInProgress surfaces attempt count and remediation notice")
    void renderRemediationInProgress_includesAttemptCount() throws Exception {
        JsonNode directive = MAPPER.readTree("""
                {"source_verdict":"REJECT","required_changes":["Fix auth"],"remediation_attempt":1,"max_remediation_attempts":1}
                """);
        MidasContext ctx = MidasContext.start("Build a CRM", "run-remediate-001")
                .withProductReviewRemediationAttempts(1)
                .withRemediationDirective(directive);

        String message = TelegramStateListener.renderRemediationInProgress(ctx);

        assertThat(message).contains("Контролер выявил недочеты");
        assertThat(message).contains("Попытка 1 из 1");
        assertThat(message).contains("Корректировка исходного кода");
    }

    @Test
    @DisplayName("renderProgress CODE_GENERATION with remediation directive shows remediation message")
    void renderProgress_codeGenerationRemediation_usesRemediationRenderer() throws Exception {
        JsonNode directive = MAPPER.readTree("""
                {"source_verdict":"REJECT","required_changes":["Fix auth"],"remediation_attempt":1,"max_remediation_attempts":1}
                """);
        MidasContext ctx = MidasContext.start("Build a CRM", "run-remediate-002")
                .withProductReviewRemediationAttempts(1)
                .withRemediationDirective(directive);

        String message = TelegramStateListener.renderProgress(MidasState.CODE_GENERATION, ctx);

        assertThat(message).contains("автоматического исправления");
        assertThat(message).doesNotContain("Генерация исходного кода...");
    }

    @Test
    @DisplayName("renderFinalCompletion after remediation notes auto-correction success")
    void renderFinalCompletion_afterRemediation_includesAutoCorrectionNote() throws Exception {
        JsonNode directive = MAPPER.readTree("""
                {"source_verdict":"REJECT","required_changes":["Fix auth"],"remediation_attempt":1,"max_remediation_attempts":1}
                """);
        MidasContext ctx = MidasContext.start("Build a CRM", "run-remediate-003")
                .withProductReviewRemediationAttempts(1)
                .withRemediationDirective(directive)
                .withProductReviewReport(MAPPER.readTree("""
                        {"verdict":"PASS","summary":"ok"}
                        """));

        String message = TelegramStateListener.renderFinalCompletion(ctx, true);

        assertThat(message).contains("Успешно исправлено автоматически");
        assertThat(message).contains("попытка 1 из 1");
        assertThat(message).contains("Контроль качества: <b>PASS</b>");
    }
}
