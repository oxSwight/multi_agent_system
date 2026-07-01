package com.midas.d3.api;

import com.midas.d3.artifact.ArtifactProperties;
import com.midas.d3.persistence.entity.MidasRunEntity;
import com.midas.d3.persistence.repository.MidasRunRepository;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PipelineArtifactService Tests")
class PipelineArtifactServiceTest {

    @Mock private PipelineOrchestrator orchestrator;
    @Mock private MidasRunRepository runRepository;

    /** Durable artifact directory; stored keys are resolved against it. */
    @TempDir Path artifactDir;

    private PipelineArtifactService service;

    @BeforeEach
    void setUp() {
        ArtifactProperties artifactProperties = new ArtifactProperties();
        artifactProperties.setDir(artifactDir.toString());
        service = new PipelineArtifactService(orchestrator, runRepository, artifactProperties);
    }

    @Test
    @DisplayName("COMPLETED run with persisted ZIP key → returns file")
    void resolveArtifactZip_completedRunWithFile_returnsFile() throws IOException {
        Files.createFile(artifactDir.resolve("artifact.zip"));
        MidasRunEntity run = MidasRunEntity.builder()
                .id("run-001")
                .rawUserIdea("idea")
                .status("COMPLETED")
                .artifactPath("artifact.zip")
                .build();

        when(orchestrator.getState("run-001")).thenReturn(MidasState.COMPLETED);
        when(runRepository.findById("run-001")).thenReturn(Optional.of(run));

        File resolved = service.resolveArtifactZip("run-001");

        assertThat(resolved).exists().isFile().hasName("artifact.zip");
        assertThat(resolved.getParentFile().toPath().toAbsolutePath().normalize())
                .isEqualTo(artifactDir.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("COMPLETED_WITH_GAPS active run with persisted ZIP key → returns file (degraded still delivers)")
    void resolveArtifactZip_completedWithGapsActive_returnsFile() throws IOException {
        Files.createFile(artifactDir.resolve("degraded.zip"));
        MidasRunEntity run = MidasRunEntity.builder()
                .id("run-degraded-active")
                .rawUserIdea("idea")
                .status("COMPLETED_WITH_GAPS")
                .artifactPath("degraded.zip")
                .build();

        when(orchestrator.getState("run-degraded-active")).thenReturn(MidasState.COMPLETED_WITH_GAPS);
        when(runRepository.findById("run-degraded-active")).thenReturn(Optional.of(run));

        assertThat(service.resolveArtifactZip("run-degraded-active")).exists().hasName("degraded.zip");
    }

    @Test
    @DisplayName("COMPLETED_WITH_GAPS persisted status (inactive machine) with ZIP key → returns file")
    void resolveArtifactZip_completedWithGapsPersisted_returnsFile() throws IOException {
        Files.createFile(artifactDir.resolve("degraded-persisted.zip"));
        MidasRunEntity run = MidasRunEntity.builder()
                .id("run-degraded-db")
                .rawUserIdea("idea")
                .status("COMPLETED_WITH_GAPS")
                .artifactPath("degraded-persisted.zip")
                .build();

        when(orchestrator.getState("run-degraded-db"))
                .thenThrow(new PipelineOrchestrator.PipelineNotFoundException("missing"));
        when(runRepository.findById("run-degraded-db")).thenReturn(Optional.of(run));

        assertThat(service.resolveArtifactZip("run-degraded-db")).exists().hasName("degraded-persisted.zip");
    }

    @Test
    @DisplayName("Active run not COMPLETED → ArtifactNotFoundException")
    void resolveArtifactZip_activeRunNotCompleted_throwsNotFound() {
        when(orchestrator.getState("run-active")).thenReturn(MidasState.CODE_GENERATION);

        assertThatThrownBy(() -> service.resolveArtifactZip("run-active"))
                .isInstanceOf(ArtifactNotFoundException.class)
                .hasMessageContaining("not COMPLETED");
    }

    @Test
    @DisplayName("COMPLETED run with missing artifact key → ArtifactNotFoundException")
    void resolveArtifactZip_noArtifactPath_throwsNotFound() {
        MidasRunEntity run = MidasRunEntity.builder()
                .id("run-no-artifact")
                .rawUserIdea("idea")
                .status("COMPLETED")
                .build();

        when(orchestrator.getState("run-no-artifact")).thenReturn(MidasState.COMPLETED);
        when(runRepository.findById("run-no-artifact")).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.resolveArtifactZip("run-no-artifact"))
                .isInstanceOf(ArtifactNotFoundException.class)
                .hasMessageContaining("no artifact archive");
    }

    @Test
    @DisplayName("COMPLETED run whose key has no file on disk → ArtifactNotFoundException")
    void resolveArtifactZip_missingFileOnDisk_throwsNotFound() {
        MidasRunEntity run = MidasRunEntity.builder()
                .id("run-missing-file")
                .rawUserIdea("idea")
                .status("COMPLETED")
                .artifactPath("gone.zip")
                .build();

        when(orchestrator.getState("run-missing-file")).thenReturn(MidasState.COMPLETED);
        when(runRepository.findById("run-missing-file")).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.resolveArtifactZip("run-missing-file"))
                .isInstanceOf(ArtifactNotFoundException.class)
                .hasMessageContaining("artifact file missing");
    }

    @Test
    @DisplayName("Unknown run ID → PipelineNotFoundException")
    void resolveArtifactZip_unknownRun_throwsPipelineNotFound() {
        when(orchestrator.getState("unknown-run"))
                .thenThrow(new PipelineOrchestrator.PipelineNotFoundException("missing"));
        when(runRepository.findById("unknown-run")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveArtifactZip("unknown-run"))
                .isInstanceOf(PipelineOrchestrator.PipelineNotFoundException.class);
    }

    @Test
    @DisplayName("Inactive DB run not COMPLETED → ArtifactNotFoundException")
    void resolveArtifactZip_inactiveDbRunNotCompleted_throwsNotFound() {
        MidasRunEntity run = MidasRunEntity.builder()
                .id("run-started")
                .rawUserIdea("idea")
                .status("STARTED")
                .build();

        when(orchestrator.getState("run-started"))
                .thenThrow(new PipelineOrchestrator.PipelineNotFoundException("missing"));
        when(runRepository.findById("run-started")).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.resolveArtifactZip("run-started"))
                .isInstanceOf(ArtifactNotFoundException.class)
                .hasMessageContaining("not COMPLETED");
    }

    // ── Path-traversal containment (P2-5 download half) ───────────────────────

    @Test
    @DisplayName("COMPLETED run whose key is an absolute path escaping the artifact dir → not served, even if it exists")
    void resolveArtifactZip_absoluteKeyOutsideDir_throwsNotFound(@TempDir Path outsideDir) throws IOException {
        // A poisoned / legacy absolute reference pointing outside midas.artifact.dir must never be
        // streamed to the client, even when the target file actually exists on disk.
        File outside = Files.createFile(outsideDir.resolve("secret.zip")).toFile();
        MidasRunEntity run = MidasRunEntity.builder()
                .id("run-traversal-abs")
                .rawUserIdea("idea")
                .status("COMPLETED")
                .artifactPath(outside.getAbsolutePath())
                .build();

        when(orchestrator.getState("run-traversal-abs")).thenReturn(MidasState.COMPLETED);
        when(runRepository.findById("run-traversal-abs")).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.resolveArtifactZip("run-traversal-abs"))
                .isInstanceOf(ArtifactNotFoundException.class)
                .hasMessageContaining("invalid artifact reference");
    }

    @Test
    @DisplayName("COMPLETED run whose key uses '../' to climb out of the artifact dir → ArtifactNotFoundException")
    void resolveArtifactZip_relativeTraversalKey_throwsNotFound() {
        MidasRunEntity run = MidasRunEntity.builder()
                .id("run-traversal-rel")
                .rawUserIdea("idea")
                .status("COMPLETED")
                .artifactPath("../../etc/passwd")
                .build();

        when(orchestrator.getState("run-traversal-rel")).thenReturn(MidasState.COMPLETED);
        when(runRepository.findById("run-traversal-rel")).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.resolveArtifactZip("run-traversal-rel"))
                .isInstanceOf(ArtifactNotFoundException.class)
                .hasMessageContaining("invalid artifact reference");
    }
}
