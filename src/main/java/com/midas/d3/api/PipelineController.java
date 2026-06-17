package com.midas.d3.api;

import com.midas.d3.api.dto.*;
import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.PipelineOrchestrator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.Map;

/**
 * REST facade over {@link PipelineOrchestrator}.
 *
 * <h2>Endpoints</h2>
 * <pre>
 *  POST   /api/v1/pipelines                 — start a new pipeline run        → 201
 *  POST   /api/v1/pipelines/{runId}/submit  — submit an LLM result            → 200
 *  GET    /api/v1/pipelines/{runId}/status  — query the current state         → 200
 *  GET    /api/v1/pipelines/{runId}/context   — full context snapshot           → 200
 *  GET    /api/v1/pipelines/{runId}/artifacts — stream persisted artifact ZIP → 200 / 404
 *  DELETE /api/v1/pipelines/{runId}         — reset / remove the run          → 204
 *  GET    /api/v1/pipelines/count           — number of in-memory active runs → 200
 * </pre>
 *
 * <p>All error cases are handled by {@link GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/pipelines")
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineOrchestrator orchestrator;
    private final PipelineArtifactService artifactService;

    // ── POST /api/v1/pipelines ────────────────────────────────────────────────

    /**
     * Starts a new pipeline run and transitions it to {@code SYSTEM_ANALYSIS}.
     *
     * @return 201 Created with the generated {@code runId} and initial state
     */
    @PostMapping({ "", "/start" })
    @ResponseStatus(HttpStatus.CREATED)
    public StartPipelineResponse startPipeline(@Valid @RequestBody StartPipelineRequest request) {
        String runId = request.isAutoMode()
                ? orchestrator.startPipelineAuto(request.rawUserIdea())
                : orchestrator.startPipeline(request.rawUserIdea());
        return new StartPipelineResponse(runId, orchestrator.getState(runId).name());
    }

    // ── POST /api/v1/pipelines/{runId}/submit ────────────────────────────────

    /**
     * Submits a raw LLM-produced string for validation at the current stage.
     * The state machine evaluates guards and either advances, retries, or moves
     * the pipeline to {@code ERROR} when retries are exhausted.
     *
     * @return 200 OK with the state after the submission event was processed
     */
    @PostMapping("/{runId}/submit")
    public PipelineStatusResponse submitResult(
            @PathVariable String runId,
            @Valid @RequestBody SubmitResultRequest request) {
        orchestrator.submitResult(runId, request.llmOutput());
        return new PipelineStatusResponse(runId, orchestrator.getState(runId).name());
    }

    // ── GET /api/v1/pipelines/{runId}/status ─────────────────────────────────

    /**
     * Returns the current public {@link com.midas.d3.statemachine.MidasState}
     * (CHOICE pseudo-states are resolved to the underlying processing stage).
     */
    @GetMapping("/{runId}/status")
    public PipelineStatusResponse getStatus(@PathVariable String runId) {
        return new PipelineStatusResponse(runId, orchestrator.getState(runId).name());
    }

    // ── GET /api/v1/pipelines/{runId}/context ────────────────────────────────

    /**
     * Returns the full {@link MidasContext} snapshot — all artifacts, audit log,
     * and execution metadata — at the current point in time.
     */
    @GetMapping("/{runId}/context")
    public PipelineContextResponse getContext(@PathVariable String runId) {
        MidasContext ctx = orchestrator.getContext(runId)
                .orElseThrow(() -> new PipelineOrchestrator.PipelineNotFoundException(
                        "No context found for run: " + runId));
        return PipelineContextResponse.from(ctx, orchestrator.getState(runId).name());
    }

    @GetMapping("/{runId}/artifacts")
    public ResponseEntity<Resource> streamArtifacts(@PathVariable String runId) {
        File zipFile = artifactService.resolveArtifactZip(runId);
        Resource resource = new FileSystemResource(zipFile);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + zipFile.getName() + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(zipFile.length())
                .body(resource);
    }

    // ── DELETE /api/v1/pipelines/{runId} ─────────────────────────────────────

    /**
     * Sends a {@code RESET} event, stops the machine, and removes it from the
     * active registry. Idempotent in the sense that any follow-up call to
     * a removed run will receive 404.
     */
    @DeleteMapping("/{runId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@PathVariable String runId) {
        orchestrator.reset(runId);
    }

    // ── GET /api/v1/pipelines/count ──────────────────────────────────────────

    /**
     * Returns the number of currently active (in-memory) pipeline runs.
     * Useful for health checks and load monitoring.
     */
    @GetMapping("/count")
    public Map<String, Integer> getActiveRunCount() {
        return Map.of("activeRuns", orchestrator.activeRunCount());
    }
}
