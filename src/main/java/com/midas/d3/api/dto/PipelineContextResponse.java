package com.midas.d3.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.midas.d3.context.AuditEntry;
import com.midas.d3.context.MidasContext;

import java.time.Instant;
import java.util.List;

/**
 * Full context snapshot returned by {@code GET /api/v1/pipelines/{runId}/context}.
 *
 * <p>Explicitly maps only the serialisable fields from {@link MidasContext},
 * avoiding accidental exposure of internal helper methods (e.g., {@code Optional<>}
 * accessors) that would produce malformed JSON.
 */
public record PipelineContextResponse(
        String runId,
        String state,
        String rawUserIdea,
        Instant createdAt,
        int validationRetries,
        String lastErrorMessage,
        JsonNode technicalSpec,
        JsonNode architectureDesign,
        JsonNode integrationStrategy,
        JsonNode generatedSourceCode,
        JsonNode featureManifest,
        JsonNode generatedTests,
        JsonNode secOpsArtifacts,
        JsonNode productReviewReport,
        int productReviewRemediationAttempts,
        JsonNode remediationDirective,
        String remediationMode,
        List<AuditEntry> auditLog
) {
    /**
     * Factory method — maps {@link MidasContext} fields plus the resolved
     * current state string into this response record.
     */
    public static PipelineContextResponse from(MidasContext ctx, String state) {
        return new PipelineContextResponse(
                ctx.getPipelineRunId(),
                state,
                ctx.getRawUserIdea(),
                ctx.getCreatedAt(),
                ctx.getValidationRetries(),
                ctx.getLastErrorMessage(),
                ctx.getTechnicalSpec(),
                ctx.getArchitectureDesign(),
                ctx.getIntegrationStrategy(),
                ctx.getGeneratedSourceCode(),
                ctx.getFeatureManifest(),
                ctx.getGeneratedTests(),
                ctx.getSecOpsArtifacts(),
                ctx.getProductReviewReport(),
                ctx.getProductReviewRemediationAttempts(),
                ctx.getRemediationDirective(),
                resolveRemediationMode(ctx),
                ctx.safeAuditLog()
        );
    }

    private static String resolveRemediationMode(MidasContext ctx) {
        JsonNode directive = ctx.getRemediationDirective();
        if (directive == null || directive.isNull() || directive.isMissingNode()) {
            return null;
        }
        JsonNode modeNode = directive.get("remediation_mode");
        if (modeNode == null || !modeNode.isTextual()) {
            return null;
        }
        String mode = modeNode.asText().strip();
        return mode.isEmpty() ? null : mode;
    }
}
