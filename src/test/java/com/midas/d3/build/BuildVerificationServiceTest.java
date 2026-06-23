package com.midas.d3.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.context.MidasContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BuildVerificationService} using a deterministic fake {@link BuildExecutor}
 * — no real toolchain is ever invoked.
 */
@DisplayName("BuildVerificationService")
class BuildVerificationServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private MidasContext contextWithSource(String sourceMapJson) throws Exception {
        JsonNode source = mapper.readTree(sourceMapJson);
        return MidasContext.start("Build a thing", "run-build-1").withGeneratedSourceCode(source);
    }

    @Test
    @DisplayName("Maven project: materializes files and reports the executor's SUCCESS verdict")
    void mavenProject_success() throws Exception {
        AtomicReference<Integer> filesSeen = new AtomicReference<>(0);
        BuildExecutor fake = (dir, tool) -> {
            assertThat(tool).isEqualTo(BuildTool.MAVEN);
            try (var paths = Files.walk(dir)) {
                filesSeen.set((int) paths.filter(Files::isRegularFile).count());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return BuildReport.success(tool, "ok");
        };
        var service = new BuildVerificationService(fake, mapper);

        BuildReport report = service.verify(contextWithSource("""
                {"pom.xml": "<project/>", "src/main/java/App.java": "class App {}"}
                """));

        assertThat(report.success()).isTrue();
        assertThat(report.tool()).isEqualTo(BuildTool.MAVEN);
        assertThat(filesSeen.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("No build descriptor: verification is skipped (treated as a pass), executor not called")
    void noDescriptor_skips() throws Exception {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        BuildExecutor fake = (dir, tool) -> { called.set(true); return BuildReport.success(tool, "x"); };
        var service = new BuildVerificationService(fake, mapper);

        BuildReport report = service.verify(contextWithSource("""
                {"index.html": "<html></html>", "style.css": "body{}"}
                """));

        assertThat(report.success()).isTrue();
        assertThat(report.tool()).isEqualTo(BuildTool.NONE);
        assertThat(called.get()).isFalse();
    }

    @Test
    @DisplayName("Failed build is surfaced as FAILED with diagnostics in the JSON artifact")
    void failedBuild_surfacedAsFailedJson() throws Exception {
        BuildExecutor fake = (Path dir, BuildTool tool) -> BuildReport.failure(
                tool, 1,
                List.of(BuildDiagnostic.error("App.java", 3, "cannot find symbol")),
                "compile failed", "[ERROR] cannot find symbol");
        var service = new BuildVerificationService(fake, mapper);

        String json = service.verifyToReportJson(contextWithSource("""
                {"pom.xml": "<project/>", "src/main/java/App.java": "class App { void x(){ y(); } }"}
                """));
        JsonNode node = mapper.readTree(json);

        assertThat(node.get("build_status").asText()).isEqualTo("FAILED");
        assertThat(node.get("tool").asText()).isEqualTo("MAVEN");
        assertThat(node.get("diagnostics")).hasSize(1);
        assertThat(node.get("diagnostics").get(0).get("message").asText()).contains("cannot find symbol");
    }

    @Test
    @DisplayName("Toolchain unavailable (executor throws) fails open to a skipped report")
    void toolchainUnavailable_failsOpen() throws Exception {
        BuildExecutor fake = (dir, tool) -> { throw new BuildExecutionException("mvn not found"); };
        var service = new BuildVerificationService(fake, mapper);

        BuildReport report = service.verify(contextWithSource("""
                {"pom.xml": "<project/>", "src/main/java/App.java": "class App {}"}
                """));

        assertThat(report.success()).isTrue();
        assertThat(report.tool()).isEqualTo(BuildTool.NONE);
        assertThat(report.summary()).contains("could not run");
    }

    @Test
    @DisplayName("Source and test maps are merged before building")
    void mergesSourceAndTests() throws Exception {
        AtomicReference<Integer> filesSeen = new AtomicReference<>(0);
        BuildExecutor fake = (dir, tool) -> {
            try (var paths = Files.walk(dir)) {
                filesSeen.set((int) paths.filter(Files::isRegularFile).count());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return BuildReport.success(tool, "ok");
        };
        var service = new BuildVerificationService(fake, mapper);

        MidasContext ctx = MidasContext.start("idea", "run-2")
                .withGeneratedSourceCode(mapper.readTree("""
                        {"pom.xml": "<project/>", "src/main/java/App.java": "class App {}"}
                        """))
                .withGeneratedTests(mapper.readTree("""
                        {"src/test/java/AppTest.java": "class AppTest {}"}
                        """));

        service.verify(ctx);

        assertThat(filesSeen.get()).isEqualTo(3);
    }
}
