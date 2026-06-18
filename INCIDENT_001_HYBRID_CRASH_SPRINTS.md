# INCIDENT-001 — HYBRID Crash Post-Mortem & Sprint Board

> **Status:** RESOLVED — all sprints complete (2026-06-18).  
> **Incident run:** `d1a9b788-c40f-411f-b5a7-b7fe33e5d26b`  
> **Opened:** 2026-06-18  
> **Resolved:** 2026-06-18  
> **Owner:** Tech Lead / Incident Commander

---

## Incident Summary

On 2026-06-18 a HYBRID pipeline run (browser extension + Spring Boot backend) consumed an estimated **~4 PLN** in Gemini API spend and terminated in **ERROR** without delivering a ZIP artifact.

### Timeline (condensed)

| Phase | Stage | Outcome |
|-------|-------|---------|
| Happy path | Agents 1–6 | Completed (~22 min). HYBRID fan-out doubled CODE_GENERATION and TEST_GENERATION LLM calls (CLIENT + SERVER passes). |
| Quality gate | Agent 7 — Controller | **REJECT** (correct). Core AI matching engine was a mock; critical/high security findings (hardcoded JWT, unsafe JPA config). |
| Remediation 1/1 | Agents 4–6 re-run | Triggered by design. Cleared generated code/tests/SecOps artifacts and re-entered CODE_GENERATION. |
| Terminal failure | SecOps (remediation pass) | **CRITICAL FAILURE** after 3 attempts. Final error: `deployment_model is CONTAINERIZED but no Dockerfile was provided`. |
| UX failure | Telegram | User saw generic `[❌ ОШИБКА]` without `lastErrorMessage`. Logs show repeated `message is not modified` on ERROR transition — likely listener/action ordering race. |
| Observability failure | PostgreSQL | All `prompt_tokens` / `completion_tokens` persisted as **0**. No FinOps visibility for this or any run. |

### Root causes

1. **Structural mismatch (HYBRID vs SecOps schema):** Pipeline generates CLIENT + SERVER surfaces in parallel, but SecOps schema allows a single `deployment_model` enum value. LLM chose `CONTAINERIZED` for the backend portion but failed to include a Dockerfile in `release_artifacts` on the remediation pass — validator correctly rejected, pipeline aborted.
2. **LLM output hygiene:** SecOps attempt 1 returned markdown-fenced JSON (` ```bash ` / code fences), causing parse failures before semantic validation could run.
3. **Expensive remediation design:** PRODUCT_REVIEW REJECT triggers a full CODE → TEST → SECOPS re-run (not a surgical patch), doubling cost on complex HYBRID projects.
4. **Blind billing:** Gemini `usageMetadata` is not wired through to `PersistenceService.logAgentExecution`.
5. **Silent UX:** Telegram ERROR render did not surface the actionable failure reason to the user.
6. **Stale runs:** Multiple prior runs with the same idea remain in `STARTED` after backend restarts (in-memory state machine lost; DB status not reconciled).

### What was NOT broken

- Controller (Agent 7) behaved correctly — mock AI engine is a valid REJECT for the stated product intent.
- SecOps validator logic for `CONTAINERIZED` is correct in isolation; it fails when HYBRID projects are forced into a single deployment model.

---

## Global Rules (mandatory for all sprint work)

These rules apply to **every** production code change in INCIDENT-001 sprints:

1. **Zero-Placeholder policy** — No `...`, `TODO`, `FIXME`, `// implement later`, or stub bodies in generated or hand-written production code. Deliverables must be runnable.
2. **No comments in production code** — Do **not** add `//` line comments or `/* */` block comments in any generated or updated production Java (or other production source) files. Self-documenting names and structure only.
3. **Minimal diff scope** — Fix only what the sprint item requires. No drive-by refactors.
4. **Tests where behavior changes** — Add or update tests for new validation rules, post-processors, persistence wiring, and Telegram rendering. Tests may use `@DisplayName` strings; production code remains comment-free.
5. **No pipeline topology changes** — Do not add `MidasState` values or alter `PipelineTopology.PROCESSING_ORDER` in this incident. Fixes are policy-layer only unless explicitly escalated.
6. **Sprint gate** — Do not start the next sprint until the current sprint is reviewed and the owner sends an explicit go-ahead (e.g. "Begin Sprint 1").

---

## Sprint Backlog

### Sprint 1 — Urgent Observability & UX

**Goal:** Stop silent failures and blind spend. User must see *why* a run failed; DB must record *how much* it cost.

**Maps to fixes:** B2, B3, B5

- [x] **S1.1 — JSON markdown fence stripping (post-processor)**
  - Add a shared sanitization step applied to raw LLM output **before** JSON parse / GoalKeeper validation for agents that emit JSON (priority: SecOps, then any agent still receiving fenced responses).
  - Strip leading/trailing markdown code fences (` ```json `, ` ``` `, etc.) and common preamble lines.
  - Reuse or extend existing `JsonSanitizer` patterns where possible; do not duplicate logic.
  - Acceptance: SecOps (and targeted agents) no longer fail with `Unrecognized token 'bash'` when the semantic JSON is valid underneath fences.

- [x] **S1.2 — Telegram ERROR message race fix**
  - Ensure `lastErrorMessage` from `CriticalFailureAction` / `PipelineErrorAction` / `ProductReviewRejectionAction` is visible in the Telegram progress message on ERROR.
  - Options (pick one in implementation): defer listener render until post-action; send a **new** follow-up message on ERROR instead of editing the progress bar; or re-read context after transition completes.
  - Acceptance: User always sees `Причина: <human-readable error>` for run `d1a9b788`-class failures; no generic-only ERROR state.

- [x] **S1.3 — Gemini token persistence**
  - Parse `usageMetadata` (prompt / candidate token counts) from `GeminiResponse`.
  - Thread counts through `LlmCallRequest` / agent result / `AgentDispatcher` into `PersistenceService.logAgentExecution`.
  - Aggregate into `midas_run.total_prompt_tokens` and `midas_run.total_completion_tokens` on each agent completion.
  - Acceptance: After a test run, `midas_agent_log` rows show non-zero tokens where Gemini returned usage; run totals reflect sum of agent logs.

- [x] **S1.4 — Sprint 1 verification**
  - Unit/integration tests green.
  - Manual smoke: force a CRITICAL_FAILURE and confirm Telegram reason + DB token rows.

**Do not begin Sprint 1 until owner command.**

---

### Sprint 2 — SecOps Resilience (HYBRID deployment model)

**Goal:** HYBRID projects must pass SecOps without forcing an impossible single `deployment_model` choice.

**Maps to fix:** B1

- [x] **S2.1 — Extend SecOps JSON schema for HYBRID**
  - Add `deployment_model: "HYBRID"` as a first-class enum value in `SecOpsEngineerValidator` and `AgentSystemPrompts.SECOPS_ENGINEER_PROMPT`.
  - Validation rules when `HYBRID`:
    - Require extension-appropriate release artifacts (packaging / store instructions) **and** a valid Dockerfile (with `FROM`) for the server component.
    - Do not require Dockerfile when model is `BROWSER_EXTENSION_PACKAGE`; do not accept extension-only artifacts when model is `CONTAINERIZED`.
  - Acceptance: Validator passes a well-formed HYBRID payload; rejects HYBRID missing either surface's artifacts.

- [x] **S2.2 — Prompt alignment**
  - Update SecOps system prompt so HYBRID runs explicitly produce dual artifact sets in one JSON object.
  - Instruct model: never wrap JSON in markdown fences (defense in depth with S1.1).
  - Acceptance: Prompt text matches validator rules; no contradictory "Dockerfile is a FAILURE" language for HYBRID server component.

- [x] **S2.3 — Regression tests**
  - Validator tests: HYBRID happy path, HYBRID missing Dockerfile, HYBRID missing extension artifacts, legacy CONTAINERIZED / BROWSER_EXTENSION_PACKAGE unchanged.
  - Acceptance: `SecOpsEngineerValidator` test suite covers new matrix.

- [x] **S2.4 — Sprint 2 verification**
  - Re-run HYBRID fixture (or recorded Controller rejection scenario) through SecOps validation offline.

**Blocked until Sprint 1 is complete and approved.**

---

### Sprint 3 — Pipeline Reliability (stuck runs & recovery)

**Goal:** No orphaned `STARTED` runs after restarts; path to resume or fail gracefully.

**Maps to fix:** B6

- [x] **S3.1 — Stuck-run reaper**
  - Scheduled job (or startup hook) marks runs as `FAILED` / `ORPHANED` when:
    - DB status is non-terminal (`STARTED`, in-progress stage names),
    - no in-memory state machine exists,
    - `updated_at` exceeds configurable TTL (e.g. 30–60 min).
  - Persist human-readable reason: `Pipeline orphaned after backend restart`.
  - Acceptance: Runs `5e2aa5df`, `0a662ddc`, etc. no longer sit in `STARTED` indefinitely.

- [x] **S3.2 — Resume / checkpoint design (minimal viable)**
  - Document and implement the smallest viable recovery:
    - **Option A (preferred MVP):** Reaper + user-facing message "Run lost — please resubmit" with link to dashboard.
    - **Option B (stretch):** Rehydrate `MidasContext` from DB audit artifacts and resume from last completed stage (only if artifacts already persisted — verify current persistence coverage first).
  - Acceptance: After backend restart, user gets explicit status within one reaper cycle; no silent `STARTED`.

- [x] **S3.3 — Status enum hygiene**
  - Ensure terminal states (`ERROR`, `COMPLETED`, `ORPHANED`/`FAILED`) are set atomically with final audit entry.
  - Acceptance: Dashboard and Telegram reflect consistent status.

- [x] **S3.4 — Sprint 3 verification**
  - Integration test: simulate start → kill in-memory machine → reaper marks orphan.
  - Manual: restart `midas_backend` container mid-run and confirm behavior.

**Blocked until Sprint 2 is complete and approved.**

---

## Out of scope (this incident)

- Tiered LLM routing / FinOps Phase 10A (separate sprint board).
- Surgical remediation patches instead of full CODE→TEST→SECOPS loop (future optimization).
- Changing `MAX_PRODUCT_REVIEW_REMEDIATIONS` or Controller strictness.

---

## Reference artifacts

| Artifact | Location |
|----------|----------|
| Failed run ID | `d1a9b788-c40f-411f-b5a7-b7fe33e5d26b` |
| Controller rejection (DB) | `midas_agent_log` where `agent_type = 'ControllerAgent'` |
| SecOps terminal error | `deployment_model is CONTAINERIZED but no Dockerfile was provided` |
| Telegram listener | `TelegramStateListener.renderError` |
| SecOps validator | `SecOpsEngineerValidator` |
| Token persistence gap | `PersistenceService.logAgentExecution` (always 0 today) |
| HYBRID fan-out | `HybridExecutionModel`, `CodeGenerationCoordinator`, `TestGenerationCoordinator` |

---

## Execution log

| Date | Sprint | Action | Result |
|------|--------|--------|--------|
| 2026-06-18 | — | Incident board created | Awaiting owner go-ahead for Sprint 1 |
| 2026-06-18 | 1 | B2/B3/B5 implemented; 346 unit tests green | Awaiting owner review before Sprint 2 |
| 2026-06-18 | 2 | B1 HYBRID SecOps schema + prompt + validator tests | Awaiting owner review before Sprint 3 |
| 2026-06-18 | 3 | B6 PipelineReaperService + stale-run cleanup; full test suite green | **INCIDENT-001 RESOLVED** |
