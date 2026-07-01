package com.midas.d3.api;

import com.midas.d3.persistence.entity.MidasRunEntity;
import com.midas.d3.persistence.repository.MidasRunRepository;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.File;

@Service
@RequiredArgsConstructor
public class PipelineArtifactService {

    private final PipelineOrchestrator orchestrator;
    private final MidasRunRepository runRepository;

    public File resolveArtifactZip(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be null or blank.");
        }

        MidasState activeState = resolveActiveState(runId);
        if (activeState != null && !isArtifactReadyState(activeState)) {
            throw new ArtifactNotFoundException(
                    "Artifacts are not available: pipeline run " + runId + " is not COMPLETED.");
        }

        MidasRunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> new PipelineOrchestrator.PipelineNotFoundException(
                        "No pipeline run found for ID: " + runId));

        if (activeState == null && !isArtifactReadyStatus(run.getStatus())) {
            throw new ArtifactNotFoundException(
                    "Artifacts are not available: pipeline run " + runId + " is not COMPLETED.");
        }

        String artifactPath = run.getArtifactPath();
        if (artifactPath == null || artifactPath.isBlank()) {
            throw new ArtifactNotFoundException(
                    "Artifacts are not available: no artifact archive for run " + runId + ".");
        }

        File file = new File(artifactPath);
        if (!file.isFile()) {
            throw new ArtifactNotFoundException(
                    "Artifacts are not available: artifact file missing for run " + runId + ".");
        }

        return file;
    }

    private MidasState resolveActiveState(String runId) {
        try {
            return orchestrator.getState(runId);
        } catch (PipelineOrchestrator.PipelineNotFoundException e) {
            return null;
        }
    }

    /**
     * Terminal states whose artifacts are downloadable. COMPLETED_WITH_GAPS is included because a
     * graceful-degradation run still delivers a real archive (partial source + MIDAS_COVERAGE_REPORT.md);
     * the client must be able to fetch it, not be told the run "is not COMPLETED".
     */
    private static boolean isArtifactReadyState(MidasState state) {
        return state == MidasState.COMPLETED || state == MidasState.COMPLETED_WITH_GAPS;
    }

    private static boolean isArtifactReadyStatus(String status) {
        return "COMPLETED".equals(status) || "COMPLETED_WITH_GAPS".equals(status);
    }
}
