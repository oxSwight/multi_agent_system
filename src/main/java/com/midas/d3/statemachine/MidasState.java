package com.midas.d3.statemachine;

/**
 * All states of the MIDAS_D3 pipeline state machine.
 *
 * <p><b>Public pipeline stages (happy path):</b>
 * IDLE → SYSTEM_ANALYSIS → ARCHITECTURE_DESIGN → INTEGRATION_STRATEGY
 *      → CODE_GENERATION → TEST_GENERATION → SECOPS_AUDIT → PRODUCT_REVIEW → COMPLETED
 *
 * <p><b>CHOICE pseudo-states</b> are internal SSM routing nodes. Each processing
 * stage transitions into its paired CHOICE state on {@code SUBMIT_RESULT}. The CHOICE
 * state then routes to either the next stage, ERROR, or back to the current stage
 * (retry) depending on validation outcome. Using CHOICE states guarantees strict
 * {@code first → then → last} evaluation order — avoiding the non-deterministic
 * multi-external-transition ordering issue in SSM 4.x.
 */
public enum MidasState {

    // ── Public pipeline states ────────────────────────────────────────────────

    /** Entry point. Machine waits here until a pipeline run is started. */
    IDLE,

    /** Agent 1 — System Analyst produces the Technical Specification. */
    SYSTEM_ANALYSIS,

    /** Agent 2 — Software Architect designs DB schema and REST API contracts. */
    ARCHITECTURE_DESIGN,

    /** Agent 3 — Integration Engineer designs external service strategies. */
    INTEGRATION_STRATEGY,

    /** Agent 4 — Implementation Engineer generates complete, runnable source code (stack-agnostic). */
    CODE_GENERATION,

    /** Agent 5 — QA Engineer generates automated test suites. */
    TEST_GENERATION,

    /**
     * Self-healing build gate — materializes the generated source + tests into a sandbox and
     * runs a real compile/build. A {@code SUCCESS} report advances to {@link #SECOPS_AUDIT};
     * a {@code FAILED} report routes back to {@link #CODE_GENERATION} with the compiler
     * diagnostics attached as a remediation directive (bounded by a remediation budget).
     * Unlike the other stages this is deterministic tooling, not an LLM agent.
     */
    BUILD_VERIFICATION,

    /** Agent 6 — SecOps Engineer audits code and produces deployment artifacts. */
    SECOPS_AUDIT,

    /**
     * Agent 7 — Controller / Product-Owner quality gate (BLOCKING). Compares the original
     * intent ({@code rawUserIdea} + {@code technicalSpec.business_goal}) against the final
     * generated artifacts and emits a PASS / PASS_WITH_NOTES / REJECT verdict. A passing
     * verdict advances the pipeline to {@link #COMPLETED}; a REJECT routes to {@link #ERROR}
     * with the conformance report attached.
     */
    PRODUCT_REVIEW,

    /**
     * Human-in-the-Loop pause state. Entered when the System Analyst determines the
     * user's idea is too ambiguous and returns a {@code [NEED_INFO]} response.
     * The machine waits here indefinitely until the {@link MidasEvent#USER_REPLIED}
     * event is received, then re-enters {@link #SYSTEM_ANALYSIS}.
     */
    WAITING_FOR_USER_INPUT,

    /** Terminal success state. All artifacts are validated and stored. */
    COMPLETED,

    /**
     * Terminal <em>graceful-degradation</em> state. Reached when {@link #CODE_GENERATION} hits an
     * unhealable functional gap (the assembled-envelope gate exhausts its self-healing budget) but a
     * best-effort partial artifact is salvageable. Instead of a client-visible {@code CRITICAL FAILURE}
     * → {@link #ERROR}, the pipeline delivers the partial source plus an honest coverage report
     * (see {@link com.midas.d3.statemachine.action.DegradeToGapsAction}). Like {@link #COMPLETED} and
     * {@link #ERROR}, it accepts {@link MidasEvent#RESET} for machine reuse.
     */
    COMPLETED_WITH_GAPS,

    /** Terminal failure state. Reached after exhausting all validation retries. */
    ERROR,

    // ── Internal SSM CHOICE pseudo-states (not part of public pipeline API) ──

    ANALYSIS_CHOICE,
    ARCHITECTURE_CHOICE,
    INTEGRATION_CHOICE,
    CODE_CHOICE,
    TEST_CHOICE,
    BUILD_CHOICE,
    SECOPS_CHOICE,
    PRODUCT_CHOICE
}
