package com.midas.d3.context;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class MidasContextTest {

    @Test
    void start_withValidArgs_createsContext() {
        var ctx = MidasContext.start("Build a todo app", "run-001");
        assertThat(ctx.getRawUserIdea()).isEqualTo("Build a todo app");
        assertThat(ctx.getPipelineRunId()).isEqualTo("run-001");
        assertThat(ctx.getCreatedAt()).isNotNull();
        assertThat(ctx.safeAuditLog()).isEmpty();
        assertThat(ctx.getValidationRetries()).isZero();
        assertThat(ctx.getProductReviewRemediationAttempts()).isZero();
    }

    @Test
    void start_withBlankIdea_throwsIllegalArgument() {
        assertThatThrownBy(() -> MidasContext.start("   ", "run-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void start_withNullIdea_throwsNullPointer() {
        assertThatThrownBy(() -> MidasContext.start(null, "run-001"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void start_withNullRunId_throwsNullPointer() {
        assertThatThrownBy(() -> MidasContext.start("idea", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void appendAudit_addsEntryImmutably() {
        var ctx = MidasContext.start("idea", UUID.randomUUID().toString());
        var entry = AuditEntry.info("STAGE", "EVENT");
        var updated = ctx.appendAudit(entry);

        assertThat(ctx.safeAuditLog()).isEmpty();
        assertThat(updated.safeAuditLog()).hasSize(1);
        assertThat(updated.safeAuditLog().get(0)).isSameAs(entry);
    }

    @Test
    void appendAudit_withNullEntry_throwsNullPointer() {
        var ctx = MidasContext.start("idea", "run-001");
        assertThatThrownBy(() -> ctx.appendAudit(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void hasRetriesExhausted_returnsCorrectly() {
        var ctx = MidasContext.start("idea", "run-001")
                .withValidationRetries(3);
        assertThat(ctx.hasRetriesExhausted(3)).isTrue();
        assertThat(ctx.hasRetriesExhausted(4)).isFalse();
    }

    @Test
    void withArtifacts_areImmutable() {
        var ctx = MidasContext.start("idea", "run-001");
        assertThat(ctx.getTechnicalSpecOpt()).isEmpty();
        assertThat(ctx.getArchitectureDesignOpt()).isEmpty();
        assertThat(ctx.getFeatureManifestOpt()).isEmpty();
        assertThat(ctx.getProductReviewReportOpt()).isEmpty();
    }

    @Test
    void withProductReviewReport_roundTripsViaAccessor() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var report = mapper.readTree("""
                {"verdict":"PASS","summary":"ok","coverage_matrix":[],"remediation_block":{}}
                """);
        var ctx = MidasContext.start("idea", "run-001")
                .withProductReviewReport(report);

        assertThat(ctx.getProductReviewReportOpt()).isPresent();
        assertThat(ctx.getProductReviewReport().get("verdict").asText()).isEqualTo("PASS");
    }

    @Test
    void withFeatureManifest_roundTripsViaAccessor() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var manifest = mapper.readTree("""
                [{"feature_id":"create-task","feature_name":"Create task","files":["src/a.js"],"entry_points":["a"]}]
                """);
        var ctx = MidasContext.start("idea", "run-001")
                .withFeatureManifest(manifest);

        assertThat(ctx.getFeatureManifestOpt()).isPresent();
        assertThat(ctx.getFeatureManifest().get(0).get("feature_id").asText()).isEqualTo("create-task");
    }

    @Test
    void withRemediationDirective_roundTripsViaAccessor() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var directive = mapper.readTree("""
                {"source_verdict":"REJECT","required_changes":["Fix assignment"],"remediation_attempt":1}
                """);
        var ctx = MidasContext.start("idea", "run-001")
                .withProductReviewRemediationAttempts(1)
                .withRemediationDirective(directive);

        assertThat(ctx.getProductReviewRemediationAttempts()).isEqualTo(1);
        assertThat(ctx.getRemediationDirectiveOpt()).isPresent();
        assertThat(ctx.getRemediationDirective().get("source_verdict").asText()).isEqualTo("REJECT");
    }
}
