package com.midas.d3.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Getter;
import lombok.With;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable central memory object for the MIDAS pipeline.
 * Each pipeline stage receives a snapshot and returns a new enriched instance.
 * Immutability prevents accidental cross-agent mutation — all "write" operations
 * produce a new instance via Lombok @With.
 */
@Getter
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class MidasContext {

    /**
     * Raw user idea submitted at pipeline entry. Enriched with clarifications
     * provided during Human-in-the-Loop pauses ({@link com.midas.d3.statemachine.MidasEvent#USER_REPLIED}).
     * Never null after creation.
     */
    @With private final String rawUserIdea;

    /** Pipeline run identifier for traceability. */
    private final String pipelineRunId;

    /** UTC timestamp of context creation. */
    private final Instant createdAt;

    // ── Agent Artifacts ──────────────────────────────────────────────────────

    /**
     * Agent 1 output: validated Technical Specification.
     * Null until the System Analyst agent completes successfully.
     */
    @With private final JsonNode technicalSpec;

    /**
     * Agent 2 output: runtime-aware architecture (architecture_style, tech_stack, components,
     * file_layout, and — only when required — data_persistence schema + api_contracts).
     * Schema-shape is stack-dependent and fields may be absent for client-side products,
     * so always treat this node defensively. Null until the Software Architect agent completes.
     */
    @With private final JsonNode architectureDesign;

    /**
     * Agent 3 output: external integration strategies.
     */
    @With private final JsonNode integrationStrategy;

    /**
     * Agent 4 output: generated source files map (filename → source code).
     */
    @With private final JsonNode generatedSourceCode;

    /**
     * Agent 5 output: generated test files map (filename → test source code).
     */
    @With private final JsonNode generatedTests;

    /**
     * Agent 6 output: security_audit_report + a runtime-appropriate release_artifacts map
     * (web-store packaging, deploy notes, or — only for CONTAINERIZED — Dockerfile/compose).
     * A Dockerfile is NOT guaranteed to be present; read defensively.
     */
    @With private final JsonNode secOpsArtifacts;

    /**
     * Agent 7 output: the Controller / Product-Owner quality-gate report — a
     * {@code verdict} (PASS | PASS_WITH_NOTES | REJECT), a {@code coverage_matrix} mapping
     * requested features to what was actually built, and a {@code remediation_block}.
     * Populated on a passing verdict (stored as the final artifact) and also attached on a
     * REJECT so the rejection rationale is retrievable. Null until the gate runs.
     */
    @With private final JsonNode productReviewReport;

    @With private final int productReviewRemediationAttempts;

    @With private final JsonNode remediationDirective;

    // ── Telegram Integration ─────────────────────────────────────────────────

    /**
     * Telegram chat ID that initiated this pipeline run via the bot interface.
     * {@code null} for REST-API-initiated runs.
     */
    @With private final Long telegramChatId;

    /**
     * ID of the Telegram message to update with pipeline progress.
     * {@code null} for REST-API-initiated runs.
     */
    @With private final Integer telegramMessageId;

    // ── Execution Metadata ───────────────────────────────────────────────────

    /**
     * Ordered list of pipeline events recorded for audit / replay.
     * Stored as immutable list — always append via withAuditLog().
     */
    @With private final List<AuditEntry> auditLog;

    /** Number of validation retries consumed in the current stage. Reset on stage advance. */
    @With private final int validationRetries;

    /** Human-readable error message if the pipeline entered ERROR state. */
    @With private final String lastErrorMessage;

    // ── Convenience Factories ────────────────────────────────────────────────

    public static MidasContext start(String rawUserIdea, String pipelineRunId) {
        Objects.requireNonNull(rawUserIdea, "rawUserIdea must not be null");
        if (rawUserIdea.isBlank()) {
            throw new IllegalArgumentException("rawUserIdea must not be blank");
        }
        Objects.requireNonNull(pipelineRunId, "pipelineRunId must not be null");

        return MidasContext.builder()
                .rawUserIdea(rawUserIdea.strip())
                .pipelineRunId(pipelineRunId)
                .createdAt(Instant.now())
                .auditLog(Collections.emptyList())
                .validationRetries(0)
                .productReviewRemediationAttempts(0)
                .build();
    }

    // ── Helper Accessors ─────────────────────────────────────────────────────

    public Optional<JsonNode> getTechnicalSpecOpt() {
        return Optional.ofNullable(technicalSpec);
    }

    public Optional<JsonNode> getArchitectureDesignOpt() {
        return Optional.ofNullable(architectureDesign);
    }

    public Optional<JsonNode> getIntegrationStrategyOpt() {
        return Optional.ofNullable(integrationStrategy);
    }

    public Optional<JsonNode> getGeneratedSourceCodeOpt() {
        return Optional.ofNullable(generatedSourceCode);
    }

    public Optional<JsonNode> getGeneratedTestsOpt() {
        return Optional.ofNullable(generatedTests);
    }

    public Optional<JsonNode> getSecOpsArtifactsOpt() {
        return Optional.ofNullable(secOpsArtifacts);
    }

    public Optional<JsonNode> getProductReviewReportOpt() {
        return Optional.ofNullable(productReviewReport);
    }

    public Optional<JsonNode> getRemediationDirectiveOpt() {
        return Optional.ofNullable(remediationDirective);
    }

    /** Returns an immutable copy of the audit log; never null. */
    public List<AuditEntry> safeAuditLog() {
        return auditLog == null ? Collections.emptyList() : Collections.unmodifiableList(auditLog);
    }

    /** Appends one entry and returns a new context instance. */
    public MidasContext appendAudit(AuditEntry entry) {
        Objects.requireNonNull(entry, "AuditEntry must not be null");
        var updated = new java.util.ArrayList<>(safeAuditLog());
        updated.add(entry);
        return this.withAuditLog(Collections.unmodifiableList(updated));
    }

    public boolean hasRetriesExhausted(int maxRetries) {
        return validationRetries >= maxRetries;
    }
}
