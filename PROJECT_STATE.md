# MIDAS — Project State & Architecture Handbook

> **Audience:** Engineers joining or maintaining the MIDAS pipeline.
> **Purpose:** A living reference for our architectural decisions, the coding
> standards we enforce on `main`, and the current state of the refactoring
> roadmap. Keep this document updated as part of any change that alters the
> pipeline shape, the context model, or the engineering mandates below.

---

## 1. Architectural Foundations

The pipeline is a seven-stage processing chain orchestrated by a Spring State
Machine. Three components form the backbone of the design: the **pipeline
topology**, the **immutable context**, and the **context reducer**. Together
they enforce a single declarative definition of routing, a tamper-proof shared
memory, and a precise contract for what each stage is allowed to read.

### 1.1 Pipeline Topology — the single declarative source of truth for routing

`PipelineTopology` (`com.midas.d3.statemachine.PipelineTopology`) is the one and
only place where the pipeline's stage ordering and routing are declared. It
exists to eliminate a long-standing maintenance hazard: prior to its
introduction, "what stage comes next" was declared in **two** independent
places that had to be kept in sync by hand — the state-machine configuration's
CHOICE targets and a parallel hardcoded `switch` in the artifact-store action.
Any change to the pipeline shape required edits in both, with a silent-divergence
risk.

Key properties:

- **One declaration point.** The happy-path order is declared exactly once, in
  the `PROCESSING_ORDER` list:

  ```
  SYSTEM_ANALYSIS → ARCHITECTURE_DESIGN → INTEGRATION_STRATEGY
                  → CODE_GENERATION → TEST_GENERATION → SECOPS_AUDIT
                  → PRODUCT_REVIEW → COMPLETED
  ```

- **Derived successor map.** The "next stage" relationship is *computed* from the
  order (`stage[i] → stage[i+1]`, with the final stage flowing to `COMPLETED`).
  Reordering, inserting, or removing a stage in `PROCESSING_ORDER` automatically
  updates every router that consults the topology.
- **CHOICE pairing.** Each processing stage is paired 1:1 with an internal SSM
  CHOICE pseudo-state (e.g. `SYSTEM_ANALYSIS` ↔ `ANALYSIS_CHOICE`). These
  pseudo-states guarantee strict `first → then → last` evaluation order during
  validation routing, sidestepping the non-deterministic multi-transition
  ordering behaviour in SSM 4.x. A processing stage transitions into its paired
  CHOICE state on `SUBMIT_RESULT`; the CHOICE state then routes forward, to
  `ERROR`, or back to the same stage (retry) based on the validation outcome.
- **Fail-fast configuration.** The constructor validates that every processing
  stage declares a CHOICE node and throws `IllegalStateException` on a
  misconfigured topology, so problems surface at startup rather than mid-run.
- **Immutable and stateless.** All maps are computed once in the constructor and
  exposed only through defensive, unmodifiable views (`processingStages()`,
  `choiceStates()`, `choiceFor(...)`, `nextStage(...)`, `isFinalStage(...)`).
- **Routing-ready by design.** Accessors are intentionally shaped so a future
  dynamic router (skip a stage for self-contained products, or insert a new
  terminal gate) can be layered on top **without changing call sites** — every
  router asks the topology "what is next" rather than embedding the answer.

### 1.2 MidasContext — strictly immutable, append-only shared memory

`MidasContext` (`com.midas.d3.context.MidasContext`) is the central memory object
that flows through the pipeline. It is a `final` class and is **strictly
immutable**: every mutating operation produces a *new* instance rather than
modifying state in place.

- **Immutability via Lombok `@With`.** Each field carries a `@With` accessor that
  returns a fresh `MidasContext` with that one field changed. There are no
  setters. Immutability prevents accidental cross-stage mutation — a stage
  receives a snapshot and returns a new, enriched instance.
- **Append-only audit log.** The `auditLog` is held as an immutable `List`. The
  only supported write path is `appendAudit(entry)`, which copies the existing
  log, appends, and returns a new context wrapping an unmodifiable list. Entries
  are never removed or reordered, giving a complete, replayable trace.
- **Controlled construction.** Instances are created through the Lombok builder
  (`toBuilder = true`) or the `start(rawUserIdea, pipelineRunId)` factory, which
  enforces non-null/non-blank inputs and seeds an empty audit log and a zeroed
  retry counter.
- **Artifact fields.** Each pipeline stage contributes a single JSON artifact
  (`technicalSpec`, `architectureDesign`, `integrationStrategy`,
  `generatedSourceCode`, `generatedTests`, `secOpsArtifacts`,
  `productReviewReport`). Artifacts default to `null` until their producing
  stage completes, and are exposed via `Optional`-returning accessors so
  consumers handle absence explicitly.

### 1.3 ContextReducer & the ArtifactDependency model — skip-aware soft dependencies

`ContextReducer` (`com.midas.d3.context.ContextReducer`) trims the full
`MidasContext` down to the minimum artifact set a given stage is permitted to
read, then serializes it to a compact JSON view (`AgentContextView`). This keeps
payloads small and, just as importantly, prevents stages from being influenced by
artifacts they must not see (for example, source code must never leak into the
System Analyst's input).

Each role (`AgentRole`) declares its upstream dependencies as a list of
`ArtifactDependency` records. This is where the **skip-aware soft dependency**
model lives:

- **`required` dependency.** Produced by a stage that *always* runs on the path to
  this role. If it is absent, that is a genuine pipeline defect — the reducer
  fails fast with `IllegalArgumentException`.
- **`optional` (soft) dependency.** Produced by a stage that dynamic routing may
  legitimately skip — for example, `integrationStrategy` for a self-contained
  product with no external services. When such an artifact is absent the reducer
  **silently omits it** and still delivers the rest of the context, logging the
  omission at debug level. A skipped stage therefore never starves a downstream
  role of its remaining context.

Current dependency declarations:

| Role | Required | Optional (skip-aware) |
| --- | --- | --- |
| `SYSTEM_ANALYST` | — | — |
| `SOFTWARE_ARCHITECT` | `technicalSpec` | — |
| `INTEGRATION_ENGINEER` | `technicalSpec`, `architectureDesign` | — |
| `IMPLEMENTATION_ENGINEER` | `technicalSpec`, `architectureDesign` | `integrationStrategy` |
| `QA_ENGINEER` | `technicalSpec`, `architectureDesign`, `generatedSourceCode` | — |
| `SECOPS_ENGINEER` | `technicalSpec`, `architectureDesign`, `generatedSourceCode`, `generatedTests` | — |
| `CONTROLLER` | `technicalSpec`, `secOpsArtifacts` | — |

The serialized view is additionally guarded by a configurable size limit
(`midas.context.max-artifact-size-kb`, default 512 KB); exceeding it raises a
`ContextSizeExceededException` rather than shipping an oversized payload.

---

## 2. Engineering Mandates (Strict Standards)

These standards are non-negotiable on the `main` branch. They are enforced in
code review and should be treated as merge blockers.

### 2.1 Zero-Placeholder Policy

All code merged to `main` must be **production-ready**. The following are not
permitted in the main branch under any circumstances:

- `// TODO`, `// FIXME`, or equivalent deferral markers.
- Stubbed, mocked, or no-op logic standing in for real behaviour.
- Dead code paths, commented-out blocks, or "temporary" shortcuts.

If a piece of work cannot be completed to production quality within a change,
it does not belong on `main`. Track follow-up work in the issue tracker, not in
source comments.

### 2.2 Test Integrity

- **The suite stays perfectly green.** `main` must never carry a failing,
  skipped, or ignored test. A red build is treated as a stop-the-line event.
- **Behaviour parity on refactors.** Any architectural change must keep observable
  behaviour identical unless the change is *explicitly* a behaviour change. When
  the shape of the pipeline, the context model, or a validator changes, the
  corresponding fixtures and tests must be updated in the same change so they
  continue to assert real, current behaviour — never weakened to "make it pass".
- **Coverage of the contract.** New routing, dependency, or validation logic ships
  with tests that pin its contract (see the existing
  `PipelineTopologyTest`, `ContextReducerTest`, and `MidasContextTest` as the
  reference standard for depth and intent).

---

## 3. Roadmap & Changelog

### Completed

**Phase 1 — Centralized Pipeline Topology.**
Consolidated all stage-ordering and routing knowledge into the single
declarative `PipelineTopology` component. Removed the duplicated, hand-synced
routing declarations (the CHOICE targets and the parallel `switch`) in favour of
a derived successor map with fail-fast startup validation. Routing is now a
single-edit change.

**Phase 2 — Skip-aware ContextReducer & semantic cleanup.**
Introduced the `ArtifactDependency` model with `required` vs `optional`
(skip-aware soft) semantics, allowing dynamically skipped stages to omit their
artifacts gracefully without starving downstream roles. Completed the semantic
rename across the codebase, replacing **`BackendDeveloper`** with
**`ImplementationEngineer`** to reflect the stack-agnostic, full-implementation
responsibility of that stage.

**Ingress Firewall (Prompt Injection Defense).**
Hardened `SYSTEM_ANALYST_PROMPT` with a strict ingress-firewall policy against
prompt injection, jailbreaks, and role-play overrides, including a safe
neutralization path for purely adversarial payloads. Strengthened
`SystemAnalystValidator` to reject leaked adversarial/meta-instructions in
specification fields and trigger the existing validation retry/error flow.
Added dedicated security regression coverage in
`IngressFirewallSecurityTest` for rejection, neutralization, false-positive
safety, and hostile payload robustness.

**Phase 3 — Controller / Product-Owner Quality Gate (core implementation).**
Added `PRODUCT_REVIEW` as the seventh processing stage after `SECOPS_AUDIT`,
with paired `PRODUCT_CHOICE` pseudo-state, `isQualityGate()` routing in
`PipelineTopology`, and derived successor to `COMPLETED`. The gate compares
original intent (`rawUserIdea` + `technicalSpec`) against shipped artifacts
(via `secOpsArtifacts.release_artifacts`) and emits a structured
`productReviewReport` with verdict `PASS | PASS_WITH_NOTES | REJECT`.

Key components shipped:

| Layer | Files |
| --- | --- |
| Topology & states | `PipelineTopology`, `MidasState` (`PRODUCT_REVIEW`, `PRODUCT_CHOICE`) |
| Context | `MidasContext.productReviewReport`, `PipelineContextResponse` |
| Agent | `ControllerAgent`, `AgentSystemPrompts.CONTROLLER_PROMPT`, orchestration wiring |
| Validation | `ControllerValidator` (schema: verdict, coverage_matrix, remediation_block) |
| SSM routing | `ProductReviewRejectedGuard`, `ProductReviewRejectionAction`, quality-gate CHOICE branch in `PipelineStateMachineConfig` |
| Reducer | `ContextReducer.AgentRole.CONTROLLER` — required: `technicalSpec`, `secOpsArtifacts` |
| Unit / SSM tests | `ControllerValidatorTest`, `ProductReviewRejectedGuardTest`, `PipelineStateMachineTest` (pass / pass-with-notes / reject / retry), `PipelineTopologyTest` |

**Reject semantics (implemented, differs from original roadmap sketch):** a
well-formed `REJECT` verdict is **terminal** — it routes to `ERROR` (not back
into the pipeline for auto-correction). `ProductReviewRejectionAction` attaches
the full report to context, sets `lastErrorMessage`, and appends an audit entry.
Schema failures at the gate still follow the standard retry/exhaustion path.

**Phase 3 — Remaining cleanup (handoff checklist).**
Closed P1 test gaps (`ContextReducerTest` CONTROLLER cases,
`MidasContextTest.productReviewReport` accessor coverage) and swept stale
"6 agents / 6 stages" copy across Telegram, Javadoc, and config to reflect
the 7-stage pipeline.

**Phase 4 — Dynamic routing (integration stage skip).**
When `has_external_integrations` is explicitly `false` on the validated
Architecture Design output or the stored Technical Specification,
`PipelineTopology.nextStage(stage, ctx)` bypasses `INTEGRATION_STRATEGY` and
routes directly to `CODE_GENERATION`. `ARCHITECTURE_CHOICE` uses
`SkipIntegrationStageGuard` as its first branch; `StoreArtifactAction` uses
context-aware routing for agent auto-dispatch. The Software Architect prompt
and `SoftwareArchitectValidator` **strictly require** `has_external_integrations`
(boolean, non-null) — omission triggers `ValidationHookException` and retry,
closing the cost-optimization leak where a missing flag defaulted to running
the Integration stage. Covered by `PipelineTopologyTest`,
`SkipIntegrationStageGuardTest`, `GoalKeeperValidatorTest`, and
`PipelineStateMachineTest`.

**Test status (2026-06-17):** `257` tests run, **0 failures** — suite green
after Phase 4 strict-schema finalization. Run: `.\mvnw.cmd test`

**Phase 5 — HYBRID Implementation Fan-Out.**
When `runtime_environment.execution_model` is `HYBRID`, `CODE_GENERATION` forks
into two bounded internal LLM passes (client surface, then server surface) inside
`CodeGenerationCoordinator`. Non-HYBRID models (`CLIENT_SIDE`, `SERVER_SIDE`, `CLI`)
bypass the fork and use the standard single-pass `IMPLEMENTATION_ENGINEER_PROMPT`.
The state machine topology is unchanged — fan-out is internal to `CODE_GENERATION`.

Key components:

| Layer | Files |
| --- | --- |
| Detection | `HybridExecutionModel`, `PipelineTopology.requiresHybridImplementationFanOut(ctx)` |
| Context slicing | `ContextReducer.reduceImplementationPass(ctx, surface)`, `ArchitectureSurfaceSlicer` |
| Orchestration | `CodeGenerationCoordinator` (used by `AgentOrchestrationService` + `ImplementationEngineerAgent`) |
| Merge | `ImplementationSourceMerger` — disjoint path union with duplicate-path fail-fast |
| Prompts | `HYBRID_CLIENT_IMPLEMENTATION_PROMPT`, `HYBRID_SERVER_IMPLEMENTATION_PROMPT` |
| Unit tests | `HybridExecutionModelTest`, `ArchitectureSurfaceSlicerTest`, `ImplementationSourceMergerTest`, `CodeGenerationCoordinatorTest`, `ContextReducerTest`, `PipelineTopologyTest`, `AgentOrchestrationServiceTest` |

**Test status (2026-06-17):** `273` tests run, **0 failures** — suite green
after Phase 5 HYBRID fan-out. Run: `.\mvnw.cmd test`

**Phase 6A — Parallel HYBRID Implementation Passes.**
When `runtime_environment.execution_model` is `HYBRID`, `CodeGenerationCoordinator`
now launches client and server implementation passes **concurrently** via
`CompletableFuture.supplyAsync` on the shared `agentTaskExecutor`, gated by
`CompletableFuture.allOf` before `ImplementationSourceMerger.merge`. Non-HYBRID
models are unchanged (single pass). `awaitAll` unwraps `CompletionException` so
`AgentExecutionException`, `LlmCallException`, and merge failures propagate to
the state machine without hanging.

| Layer | Files |
| --- | --- |
| Orchestration | `CodeGenerationCoordinator` — parallel fan-out + exception unwrapping |
| Executor | `AsyncConfig.AGENT_EXECUTOR` injected into coordinator |
| Unit tests | `CodeGenerationCoordinatorTest` — order-independent prompt assertions, parallel failure propagation |

**Test status (2026-06-17):** `274` tests run, **0 failures** — suite green
after Phase 6A parallel HYBRID passes. Run: `.\mvnw.cmd test`

**Phase 6B — Parallel HYBRID Test Generation Fan-Out.**
When `runtime_environment.execution_model` is `HYBRID`, `TEST_GENERATION` forks
into two bounded internal LLM passes (client QA surface, server QA surface) inside
`TestGenerationCoordinator`, launched concurrently via `CompletableFuture.supplyAsync`
on the shared `agentTaskExecutor` and gated by `CompletableFuture.allOf` before
`ImplementationSourceMerger.merge`. Non-HYBRID models bypass the fork and use the
standard single-pass `QA_ENGINEER_PROMPT`. The state machine topology is unchanged —
fan-out is internal to `TEST_GENERATION`.

| Layer | Files |
| --- | --- |
| Detection | `HybridExecutionModel` (reused from Phase 5) |
| Source slicing | `SourceMapSlicer` — filters `generatedSourceCode` by client/server path |
| Context slicing | `ContextReducer.reduceTestGenerationPass(ctx, surface)` + `ArchitectureSurfaceSlicer` |
| Orchestration | `TestGenerationCoordinator` (used by `AgentOrchestrationService` + `QaAutomationAgent`) |
| Merge | `ImplementationSourceMerger` — disjoint test-path union with duplicate-path fail-fast |
| Prompts | `HYBRID_CLIENT_QA_PROMPT`, `HYBRID_SERVER_QA_PROMPT` |
| Unit tests | `SourceMapSlicerTest`, `TestGenerationCoordinatorTest`, `AgentOrchestrationServiceTest` |

**Test status (2026-06-17):** `281` tests run, **0 failures** — suite green
after Phase 6B HYBRID test fan-out. Run: `.\mvnw.cmd test`

**Phase 7A — Execution-Model Surface Routing.**
`CLIENT_SIDE` and `SERVER_SIDE` projects now route through a single bounded
surface pass at both `CODE_GENERATION` and `TEST_GENERATION`, using the same
`HYBRID_CLIENT_*` / `HYBRID_SERVER_*` prompts and context slicing as the HYBRID
fan-out passes (`ArchitectureSurfaceSlicer` for architecture; `SourceMapSlicer`
for QA source filtering). `HYBRID` retains parallel client/server fan-out;
`CLI` retains the generic single-pass prompts. SSM topology unchanged.

| Layer | Files |
| --- | --- |
| Detection | `HybridExecutionModel.singlePassSurface(ctx)` |
| Orchestration | `CodeGenerationCoordinator`, `TestGenerationCoordinator` — surface-routed single pass |
| Context slicing | `ContextReducer.reduceImplementationPass`, `reduceTestGenerationPass` (reused) |
| Unit tests | `CodeGenerationCoordinatorTest`, `TestGenerationCoordinatorTest`, `HybridExecutionModelTest` |

**Test status (2026-06-17):** `287` tests run, **0 failures** — suite green
after Phase 7A surface routing. Run: `.\mvnw.cmd test`

### Planned — Phase 6C+ / 7B (TBD)

> **Branch:** `main`
> **Candidates:** Further dynamic routing, HITL Controller feedback loop.

### Next Up (Post Phase 5)

- Human-in-the-loop review of Controller REJECT reports before pipeline reset.
- Extend dynamic routing to additional skip-eligible stages if product rules emerge.

---

### Previously — Phase 3 Handoff Checklist (archived)

> **Test status (2026-06-17):** `240` tests run, **0 failures** — suite green after
> `PipelineControllerIT` happy-path + REJECT IT fixes.

#### P0 — Unblock green build ✅ (done)

1. ~~**`PipelineControllerIT.happyPath_fullPipeline_completesWithAllArtifacts`**~~ — fixed:
   7th submit with `VALID_CONTROLLER`; asserts `productReviewReport`.

2. ~~**Add IT for REJECT path**~~ — added `productReview_reject_routesToErrorWithReport`.

#### P1 — Test coverage gaps ✅ (done)

3. ~~**`ContextReducerTest`** — CONTROLLER happy path + fail-fast cases.~~

4. ~~**`MidasContextTest`** — `productReviewReport` accessor coverage.~~

5. **Optional:** unit test for `ProductReviewRejectionAction` (report attached,
   audit entry, error message populated).

#### P2 — Stale "6 agents" references ✅ (done)

Several files still describe a 6-stage / 6-agent pipeline. Update to 7:

| File | What to fix |
| --- | --- |
| `TelegramStateListener` | Javadoc "6 segments"; progress bars for stages 1–6; `COMPLETED` message says "All 6 artifacts" — add 7th segment / update copy |
| `TelegramPipelineBot` | Javadoc "6 stages" |
| `AgentSystemPrompts` | Class Javadoc "6 pipeline agents" |
| `AsyncConfig` | Comment "6 agents" pool sizing note |
| `MidasState` | Class-level happy-path diagram missing `PRODUCT_REVIEW` |
| `PipelineControllerIT` | Comment "Full happy-path through all 6 stages" |

#### P3 — After green + cleanup ✅ (done)

6. ~~Re-run full suite (`.\mvnw.cmd test`) and confirm `250` tests, zero failures.~~
7. ~~Commit Phase 3 changes on the feature branch.~~

---

## 4. Agent Handoff Snapshot (2026-06-17)

**Working tree:** Phase 7A complete — execution-model surface routing verified.

**Phase 7A verification (complete):**

1. `CLIENT_SIDE` routes to a single client-surface pass with `HYBRID_CLIENT_*` prompts
   and sliced architecture/source context at both `CODE_GENERATION` and `TEST_GENERATION`.
2. `SERVER_SIDE` routes to a single server-surface pass with `HYBRID_SERVER_*` prompts
   (JUnit/RestAssured QA without front-end noise).
3. `HYBRID` unchanged — parallel client/server fan-out with merge.
4. `CLI` unchanged — generic single pass with `IMPLEMENTATION_ENGINEER_PROMPT` /
   `QA_ENGINEER_PROMPT`.
5. SSM topology unchanged — routing is internal to the coordinators.

**Do not** change REJECT→ERROR semantics unless product owner explicitly
requests a feedback-loop design; current behaviour is intentional and covered
by `PipelineStateMachineTest` + `ProductReviewRejectedGuardTest`.

---

*Maintenance note: update this document in the same change that alters the
pipeline topology, the context model, the reducer dependency map, or the
roadmap. It is the central reference point for the project's state.*
