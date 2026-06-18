# Phase 10 — FinOps Optimization Sprint Board

> **Status:** Approved — execution pending sprint-by-sprint sign-off.  
> **Audience:** Human maintainers and LLM agent sessions working on MIDAS D3.  
> **Purpose:** Single source of truth for Phase 10A → 10C → 10B. Do not deviate without explicit owner approval.

---

## 1. Context & FinOps Goals

MIDAS D3 is a seven-agent autonomous software generation pipeline (Telegram → progress bar → ZIP). Phase 10 optimizes **unit economics (COGS)** and **Controller quality** without sacrificing the Zero-Placeholder deliverable contract.

| Goal | Target |
|------|--------|
| Tiered LLM routing | Flash on cheap stages; Pro on code/test generation |
| Token observability | Real per-invocation model + token counts in DB and dashboard |
| Controller quality gap | `feature_manifest` gives PO gate file-level evidence without raw source |
| Remediation economics | Surgical patch first; full regen only as in-attempt fallback |

**Expected COGS impact (order of magnitude):**

- 10A: 25–40% blended savings via Pro-only on heavy stages + fewer Flash retries
- 10C: Indirect — fewer false REJECTs and better remediation targeting
- 10B: 50–70% savings on remediation runs that previously full-regenerated stages 4–6

---

## 2. Architecture Rules (Absolute Constraints)

All Phase 10 work **MUST** obey these rules. Violations require explicit owner approval.

### Pipeline & State Machine

- **No new `MidasState` values.** Patch vs full regen is a context/coordinator concern only.
- **Do not modify `PipelineTopology.PROCESSING_ORDER`** or CHOICE branch ordering in `PipelineStateMachineConfig`.
- **Do not add SSM transitions, guards, or actions** for FinOps features unless explicitly scoped in a future phase.
- **`ContextReducer.ARTIFACT_DEPENDENCIES`** may only be **extended** (new keys/methods). Do not remove skip-aware optional semantics.

### Agent & Output Contracts

- **Zero-Placeholder policy remains absolute** on all generated source and test files (`// TODO`, stubs, `UnsupportedOperationException`, etc.).
- **LLM boundary envelope (10C):** Implementation returns `{ source_files, feature_manifest }`; storage splits into `MidasContext.generatedSourceCode` (flat map) and `MidasContext.featureManifest`.
- **Downstream QA/SecOps** continue consuming flat `generatedSourceCode` only — no wrapper rippling.
- **Controller never receives raw source or test bodies** — only `technicalSpec`, `secOpsArtifacts`, and (after 10C) `featureManifest`.

### Remediation (10B)

- **`PipelineTopology.MAX_PRODUCT_REVIEW_REMEDIATIONS = 1`** — unchanged.
- **One remediation attempt = one dispatch budget:** `(Patch Try → validate → merge)` → on failure → `(Full Regen Try)` within the **same** `CODE_GENERATION` entry. No second remediation loop for fallback.
- **Surgical patch retains** `generatedSourceCode`, `generatedTests`, `featureManifest`. **Always clears** `secOpsArtifacts`.

### Code & Documentation Discipline (Agent Sessions)

- **No code comments** unless an existing file already uses them for non-obvious logic — match surrounding style; do not add new explanatory comments.
- **Do not modify `PROJECT_STATE.md`** during Phase 10 sprints unless the active sprint explicitly includes it in its checklist.
- **One sprint at a time.** Green `mvn test` gate before marking a sprint complete.
- **No drive-by refactors** outside sprint target classes.

### Context Size

- **`midas.context.max-artifact-size-kb` (512)** remains enforced.
- Patch planner must force `FULL_REGEN` when patch context estimate exceeds **75%** of cap.

---

## 3. Approved Blueprint Decisions

| # | Question | Approved Answer |
|---|----------|-----------------|
| 1 | **Build sequence** | **10A → 10C → 10B.** Manifest must ship before patch path resolution. |
| 2 | **Model IDs** | **`gemini-1.5-pro`** for `CODE_GENERATION` and `TEST_GENERATION`. **`gemini-1.5-flash`** for all other pipeline stages. `EvolutionAgent` stays on Flash/default — excluded from stage map. |
| 3 | **Implementation envelope** | **Approved:** `{ source_files, feature_manifest }` at LLM boundary; split storage in `MidasContext`. |
| 4 | **Patch fallback** | **Confirmed:** one remediation attempt = Patch Try → if fail → Full Regen Try; single dispatch budget. |
| 5 | **DB migration V3** | **Confirmed:** extend `midas_agent_log` — add **`model_id`**; ensure **`prompt_tokens`** and **`completion_tokens`** are populated from real LLM usage (columns exist in V1 but are currently written as `0`). |

---

## 4. Cross-Phase Foundations (Delivered in Sprint 10A-1)

These artifacts are shared across all sprints:

| Artifact | Owner Sprint |
|----------|--------------|
| `LlmCallResult` record | 10A-1 |
| `LlmCallRequest.modelOverride` | 10A-1 |
| `LlmModelPolicy` + `LlmModelPolicyProperties` | 10A-1 |
| `GeminiResponse.usageMetadata` parsing | 10A-1 |
| `AgentDispatcher` → real token persistence | 10A-2 |
| Flyway **`V3__agent_log_finops.sql`** (`model_id` column) | 10A-2 |

---

## 5. Sprint Backlog

Use checkboxes to track progress. Mark `[x]` only when implemented **and** tests pass.

---

### Sprint 10A-1 — LLM Result Type & Model Policy

**Objective:** Per-request model routing infrastructure without changing pipeline behavior.

**Target classes:** `LlmCallResult`, `LlmCallRequest`, `LlmClient`, `GeminiLlmClient`, `GeminiResponse`, `NousRestClient`, `LlmModelPolicy`, `LlmModelPolicyProperties`, `application.yml`, `application-test.yml`

- [ ] Create `com.midas.d3.llm.LlmCallResult` (text, modelUsed, promptTokens, completionTokens)
- [ ] Add optional `modelOverride` to `LlmCallRequest`; update `of(...)` factory
- [ ] Change `LlmClient.call()` return type to `LlmCallResult`; rename/document `modelId()` → `defaultModelId()`
- [ ] Create `LlmModelPolicy` mapping `MidasState` → model ID with fallback to `midas.llm.model`
- [ ] Create `LlmModelPolicyProperties` bound to `midas.llm.stage-models.*`
- [ ] Configure `application.yml`: default `gemini-1.5-flash`; Pro override for `CODE_GENERATION`, `TEST_GENERATION`
- [ ] Update `GeminiResponse` to deserialize `usageMetadata` token counts
- [ ] Update `GeminiLlmClient` to use per-request model in URL path and return `LlmCallResult`
- [ ] Update `NousRestClient` for `LlmCallResult` compatibility (single-model fallback acceptable)
- [ ] Create `LlmModelPolicyTest` — all stages resolve; unknown → fallback
- [ ] Update `GeminiLlmClientTest` for per-request model path and usage parsing

**Gate:** Unit tests for LLM layer green; no agent integration yet.

---

### Sprint 10A-2 — Wire Policy Through Agents & Persistence

**Objective:** All pipeline LLM call sites use tiered models; DB records real usage.

**Target classes:** `BaseMidasAgent`, `CodeGenerationCoordinator`, `TestGenerationCoordinator`, `AgentOrchestrationService`, `AgentDispatcher`, `PersistenceService`, `MidasAgentLogEntity`, `AgentLogDto`, `DashboardService`, `db/migration/V3__agent_log_finops.sql`

- [ ] Wire `LlmModelPolicy.resolve(stage)` in `BaseMidasAgent.execute()`
- [ ] Wire model policy in `CodeGenerationCoordinator` (all pass types including HYBRID fan-out)
- [ ] Wire model policy in `TestGenerationCoordinator` (all pass types including HYBRID fan-out)
- [ ] Wire model policy in `AgentOrchestrationService` (REST/manual path parity)
- [ ] Update all `llmClient.call()` consumers to handle `LlmCallResult`
- [ ] Create Flyway **`V3__agent_log_finops.sql`**: `ALTER TABLE midas_agent_log ADD COLUMN model_id VARCHAR(100)`
- [ ] Add `modelId` field to `MidasAgentLogEntity`
- [ ] Update `PersistenceService.logAgentExecution()` to accept and persist `modelId`, `promptTokens`, `completionTokens`
- [ ] Update `AgentDispatcher.runAgent()` to pass real values from `LlmCallResult` (replace hardcoded `0, 0`)
- [ ] Update `AgentLogDto` and dashboard queries to expose `modelId` per invocation
- [ ] Update `BaseMidasAgentTest`, `CodeGenerationCoordinatorTest`, `TestGenerationCoordinatorTest`, `AgentOrchestrationServiceTest`
- [ ] Add or extend `AgentDispatcherTest` — verifies non-zero tokens persisted on success
- [ ] Confirm `EvolutionAgent` explicitly uses default Flash model (not stage map)

**Gate:** Full `mvn test` green; `PipelineStateMachineTest` unchanged/passing; manual spot-check log output shows `model=gemini-1.5-pro` on CODE_GENERATION.

**Sprint 10A complete when:** 10A-1 and 10A-2 all checked.

---

### Sprint 10C-1 — Context Field & Envelope Validation

**Objective:** Define manifest storage and LLM envelope schema at validation boundary.

**Target classes:** `MidasContext`, `ImplementationEngineerValidator`, `FeatureManifestValidator`, `ImplementationOutputUnwrapper`, `AgentSystemPrompts` (constant names only — prompt text authored in this sprint)

- [ ] Add `@With JsonNode featureManifest` to `MidasContext`
- [ ] Add `getFeatureManifestOpt()` helper on `MidasContext`
- [ ] Create `FeatureManifestValidator` — feature_id, feature_name, files[], entry_points[] rules
- [ ] Extend `ImplementationEngineerValidator` to validate envelope: `source_files` + `feature_manifest`
- [ ] Enforce: every `core_features[].id` from spec appears in manifest; every manifest file exists in `source_files`
- [ ] Create `ImplementationOutputUnwrapper` — splits validated envelope into source map + manifest array
- [ ] Update `IMPLEMENTATION_ENGINEER_PROMPT` to require envelope schema
- [ ] Update `HYBRID_CLIENT_IMPLEMENTATION_PROMPT` and `HYBRID_SERVER_IMPLEMENTATION_PROMPT` for partial manifest per surface
- [ ] Create `FeatureManifestValidatorTest`
- [ ] Create `ImplementationOutputUnwrapperTest`
- [ ] Extend or create `ImplementationEngineerValidatorTest` for envelope accept/reject cases
- [ ] Update `MidasContextTest`

**Gate:** Validator and unwrapper unit tests green; no coordinator integration yet.

---

### Sprint 10C-2 — Coordinator Merge, Storage & HYBRID Manifest

**Objective:** Implementation stage produces and stores split artifacts end-to-end.

**Target classes:** `CodeGenerationCoordinator`, `FeatureManifestMerger`, `StoreArtifactAction`, `ImplementationSourceMerger` (reference only — do not conflate with manifest merge)

- [ ] Create `FeatureManifestMerger` — merge client/server manifest arrays; dedupe by `feature_id`; fail on conflict
- [ ] Update `CodeGenerationCoordinator` single-pass flow: validate envelope → unwrap → return/store both parts
- [ ] Update HYBRID fan-out: merge sources via `ImplementationSourceMerger` + manifests via `FeatureManifestMerger`
- [ ] Update `StoreArtifactAction.applyArtifact(CODE_GENERATION)` to store unwrapped `source_files` → `generatedSourceCode` and `feature_manifest` → `featureManifest`
- [ ] Handle envelope in `LAST_VALIDATED_NODE` at store time if coordinator submits envelope JSON
- [ ] Update `CodeGenerationCoordinatorTest` — unwrap, HYBRID manifest merge, stored split
- [ ] Create `FeatureManifestMergerTest`
- [ ] Add `StoreArtifactActionTest` (or extend state machine IT) — split storage verified
- [ ] Update `PipelineStateMachineTest` happy path — `featureManifest` non-null after CODE_GENERATION

**Gate:** Code generation integration tests green.

---

### Sprint 10C-3 — Controller Consumption & API Exposure

**Objective:** Controller and external API see manifest; QA/SecOps unchanged.

**Target classes:** `ContextReducer`, `ControllerAgent`, `AgentSystemPrompts.CONTROLLER_PROMPT`, `ControllerValidator`, `PipelineContextResponse`, `ArtifactPackagingService` (optional)

- [ ] Add `ArtifactDependency.required("featureManifest")` to `CONTROLLER` in `ContextReducer.ARTIFACT_DEPENDENCIES`
- [ ] Add `"featureManifest"` case in `ContextReducer.resolveArtifact()`
- [ ] Update `CONTROLLER_PROMPT` — judge coverage using manifest files/entry_points + secOps release_artifacts
- [ ] Optionally extend `ControllerValidator` — evidence must reference manifest feature_id or file path when manifest present
- [ ] Add `featureManifest` field to `PipelineContextResponse` and `from()` factory
- [ ] Update `ContextReducerTest` — Controller view includes manifest; QA view does not
- [ ] Update `ControllerValidatorTest` if evidence rules added
- [ ] Update `PipelineContextResponseTest`
- [ ] Update `PipelineControllerIT` if context endpoint assertions needed
- [ ] Optional: include `_midas_feature_manifest.json` in ZIP via `ArtifactPackagingService`

**Gate:** Full `mvn test` green; Controller receives manifest on path to PRODUCT_REVIEW.

**Sprint 10C complete when:** 10C-1, 10C-2, and 10C-3 all checked.

---

### Sprint 10B-1 — Remediation Planner & Directive Schema

**Objective:** Remediation mode and affected paths resolved from manifest + coverage gaps.

**Target classes:** `RemediationMode`, `PatchRemediationPlanner`, `RemediationInitAction`

- [ ] Create `RemediationMode` enum: `SURGICAL_PATCH`, `FULL_REGEN`
- [ ] Create `PatchRemediationPlanner` — decision tree per approved roadmap
- [ ] Extend `RemediationInitAction.buildRemediationDirective()` with `remediation_mode`, `affected_paths`, `affected_features`
- [ ] Planner resolves paths via `featureManifest` (primary) and `coverage_matrix[].evidence` (fallback)
- [ ] Planner forces `FULL_REGEN` when >50% of `architecture.file_layout` affected or patch context >75% of 512 KB cap
- [ ] Update `RemediationInitAction` artifact invalidation matrix — surgical retains source/tests/manifest; clears secOps only
- [ ] Create `PatchRemediationPlannerTest` — manifest-present, absent, HYBRID wide gap, empty gaps
- [ ] Extend `PipelineStateMachineTest` — surgical directive retains `generatedSourceCode`
- [ ] Update `MidasContextTest` / directive shape tests if applicable

**Gate:** Planner and remediation init unit tests green.

---

### Sprint 10B-2 — Patch Infrastructure (Slicer, Patcher, Reducer)

**Objective:** Core merge/slice utilities and ContextReducer patch methods.

**Target classes:** `SourceMapPatcher`, `SourceMapPathFilter` (or extend `SourceMapSlicer`), `ContextReducer`, `PatchValidationException`

- [ ] Create `SourceMapPatcher` — apply patch map onto baseline; fail on illegal paths/conflicts
- [ ] Create `SourceMapPathFilter` — extract subset of source/test map by path list (+ optional dependency paths from architecture)
- [ ] Create `PatchValidationException` for coordinator fallback signaling
- [ ] Add `ContextReducer.reducePatchImplementationPass(ctx, affectedPaths)`
- [ ] Add `ContextReducer.reducePatchTestPass(ctx, affectedPaths, patchedSource)`
- [ ] Add `ContextReducer.reducePatchSecOpsPass(ctx, affectedPaths, patchedSource, patchedTests)`
- [ ] Patch reducer methods compose on existing deps — do not alter global `ARTIFACT_DEPENDENCIES` map for standard roles
- [ ] Create `SourceMapPatcherTest`
- [ ] Create `SourceMapPathFilterTest`
- [ ] Update `ContextReducerTest` for all patch reducer methods

**Gate:** Utility and reducer tests green.

---

### Sprint 10B-3 — Coordinator Patch Execution & Prompts

**Objective:** CODE_GENERATION and TEST_GENERATION run patch-first with in-attempt full-regen fallback.

**Target classes:** `CodeGenerationCoordinator`, `TestGenerationCoordinator`, `AgentSystemPrompts`, `ImplementationEngineerValidator`, `QaEngineerValidator`

- [ ] Add `IMPLEMENTATION_PATCH_PROMPT`, `QA_PATCH_PROMPT` to `AgentSystemPrompts`
- [ ] Extend `appendProductReviewRemediation` with patch scope block (affected_paths, forbidden full rewrite)
- [ ] Branch `CodeGenerationCoordinator.execute()` on `remediationDirective.remediation_mode`
- [ ] Patch path: reduce → LLM (Pro) → merge via `SourceMapPatcher` → validate full merged map
- [ ] On patch validation failure: log audit WARN → execute existing full pass (same dispatch, no extra remediation loop)
- [ ] HYBRID patch: split affected paths by surface; fan-out only surfaces with affected paths; copy untouched files from baseline
- [ ] Branch `TestGenerationCoordinator.execute()` — delta tests for affected paths → merge into `generatedTests`
- [ ] Extend `ImplementationEngineerValidator` / `QaEngineerValidator` for patch subset acceptance when mode flag present in context or validator param
- [ ] Update `CodeGenerationCoordinatorTest` — patch prompt, merge, fallback to full pass
- [ ] Update `TestGenerationCoordinatorTest` — delta generation + merge

**Gate:** Coordinator tests green.

---

### Sprint 10B-4 — SecOps Delta, UX & End-to-End Remediation

**Objective:** SecOps re-audit scoped changes; Telegram reflects mode; full pipeline remediation E2E.

**Target classes:** `BaseMidasAgent` (SecOps prompt path), `AgentSystemPrompts.SECOPS_DELTA_PROMPT`, `SecOpsEngineerValidator`, `TelegramStateListener`, `PipelineContextResponse`

- [ ] Add `SECOPS_DELTA_PROMPT` (or extend SecOps effective prompt hook) for patch remediation runs
- [ ] SecOps patch path uses `reducePatchSecOpsPass` — output schema unchanged (full SecOps JSON)
- [ ] Confirm `StoreArtifactAction` stores merged artifacts correctly after patch flow
- [ ] Update `TelegramStateListener` — differentiate "Surgical correction" vs "Full regeneration" during remediation re-entry
- [ ] Expose `remediation_mode` in `PipelineContextResponse` via directive (if not already visible)
- [ ] Update `TelegramStateListenerTest`
- [ ] Extend `PipelineStateMachineTest`:
  - [ ] REJECT → surgical patch → PASS
  - [ ] REJECT → patch fail → full regen → PASS
  - [ ] Second REJECT → ERROR (remediation exhausted)
- [ ] Full `mvn test` regression

**Gate:** Full suite green; remediation E2E scenarios pass.

**Sprint 10B complete when:** 10B-1 through 10B-4 all checked.

---

## 6. Phase Completion Criteria

| Phase | Complete When |
|-------|---------------|
| **10A** | Tiered models active; `midas_agent_log` rows have real `model_id`, `prompt_tokens`, `completion_tokens` |
| **10C** | Every successful CODE_GENERATION stores `featureManifest`; Controller requires it; API exposes it |
| **10B** | Remediation defaults to surgical patch; full regen is fallback only; single-attempt budget honored |

---

## 7. Explicitly Out of Scope (Phase 10)

- New `MidasState` values or pipeline stages
- Prompt caching / Gemini context cache integration
- Pricing tier billing logic for end users
- Skipping QA or SecOps stages dynamically
- Changes to `EvolutionAgent` analysis pipeline (except Flash default confirmation)
- `PROJECT_STATE.md` updates (deferred until owner requests)

---

## 8. Agent Session Protocol

When an LLM agent session picks up Phase 10 work:

1. Read this file first.
2. Identify the **first unchecked sprint** in build order (10A-1 → … → 10B-4).
3. Implement **only** that sprint's tasks.
4. Run `mvn test` (or `.\mvnw.cmd test` on Windows).
5. Check off completed tasks in this file.
6. Stop and wait for owner sign-off before starting the next sprint.

**Do not skip sprints.** **Do not combine 10C and 10B work** in one session unless the owner explicitly overrides.

---

*Last updated: Phase 10 roadmap approval — build order 10A → 10C → 10B.*
