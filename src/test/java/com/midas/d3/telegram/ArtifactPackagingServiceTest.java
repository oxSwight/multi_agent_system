package com.midas.d3.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.midas.d3.artifact.ArtifactProperties;
import com.midas.d3.context.MidasContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ArtifactPackagingService}.
 *
 * <p>Uses a real {@link ObjectMapper} and real file-system operations via JUnit 5's
 * {@link TempDir} (auto-cleaned after each test). No Spring context is required.
 */
@DisplayName("ArtifactPackagingService Tests")
class ArtifactPackagingServiceTest {

    private ArtifactPackagingService service;
    private ObjectMapper             objectMapper;

    /** Durable artifact directory the service writes result ZIPs into (auto-cleaned per test). */
    @TempDir
    Path artifactDir;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        ArtifactProperties artifactProperties = new ArtifactProperties();
        artifactProperties.setDir(artifactDir.toString());
        service = new ArtifactPackagingService(objectMapper, artifactProperties);
    }

    // ── README always present ─────────────────────────────────────────────────

    @Test
    @DisplayName("Empty context (no artifacts) produces a valid ZIP containing the pipeline report")
    void packageResults_emptyContext_zipContainsReport() throws IOException {
        MidasContext ctx = MidasContext.start("My idea", "run-empty-001");

        File zip = service.packageResults(ctx);
        try {
            assertThat(zip).isNotNull().exists().isFile();
            assertThat(zip.getName()).startsWith("midas_result_").endsWith(".zip");

            Set<String> entries = listZipEntries(zip);
            // No project artifact → no synthesized README, but the run report is always present.
            assertThat(entries).contains("MIDAS_PIPELINE_REPORT.md");
        } finally {
            zip.delete();
        }
    }

    @Test
    @DisplayName("MIDAS_PIPELINE_REPORT.md contains the pipeline run ID and raw idea")
    void packageResults_report_containsRunMetadata() throws IOException {
        MidasContext ctx = MidasContext.start("Build a task tracker", "run-meta-001");

        File zip = service.packageResults(ctx);
        try {
            String report = readZipEntry(zip, "MIDAS_PIPELINE_REPORT.md");
            assertThat(report)
                    .contains("run-meta-001")
                    .contains("Build a task tracker");
        } finally {
            zip.delete();
        }
    }

    // ── Stage 1: technicalSpec ────────────────────────────────────────────────

    @Test
    @DisplayName("technicalSpec present → ZIP contains 1_SystemAnalysis.md with JSON content")
    void packageResults_withTechnicalSpec_containsMarkdown() throws IOException {
        ObjectNode spec = objectMapper.createObjectNode();
        spec.put("business_goal", "Manage tasks efficiently");
        spec.putArray("core_features").add("CRUD");

        MidasContext ctx = MidasContext.start("Task app", "run-spec-001")
                .withTechnicalSpec(spec);

        File zip = service.packageResults(ctx);
        try {
            Set<String> entries = listZipEntries(zip);
            assertThat(entries).contains("1_SystemAnalysis.md");

            String content = readZipEntry(zip, "1_SystemAnalysis.md");
            assertThat(content)
                    .contains("```json")
                    .contains("business_goal")
                    .contains("Manage tasks efficiently");
        } finally {
            zip.delete();
        }
    }

    // ── Stage 4: generatedSourceCode (filename → source map) ─────────────────

    @Test
    @DisplayName("generatedSourceCode map → individual .java files at their verbatim paths")
    void packageResults_withSourceCode_createsSourceFiles() throws IOException {
        ObjectNode code = objectMapper.createObjectNode();
        code.put("com/example/TaskController.java", "public class TaskController {}");
        code.put("com/example/TaskService.java",    "public class TaskService {}");

        MidasContext ctx = MidasContext.start("Task app", "run-code-001")
                .withGeneratedSourceCode(code);

        File zip = service.packageResults(ctx);
        try {
            Set<String> entries = listZipEntries(zip);
            assertThat(entries)
                    .contains("com/example/TaskController.java")
                    .contains("com/example/TaskService.java");

            String content = readZipEntry(zip, "com/example/TaskController.java");
            assertThat(content).isEqualTo("public class TaskController {}");
        } finally {
            zip.delete();
        }
    }

    // ── Stage 5: generatedTests ───────────────────────────────────────────────

    @Test
    @DisplayName("generatedTests map → test files placed at their verbatim paths")
    void packageResults_withGeneratedTests_createsTestFiles() throws IOException {
        ObjectNode tests = objectMapper.createObjectNode();
        tests.put("src/test/java/com/example/TaskControllerTest.java",
                "class TaskControllerTest { @Test void test(){} }");

        MidasContext ctx = MidasContext.start("Task app", "run-tests-001")
                .withGeneratedTests(tests);

        File zip = service.packageResults(ctx);
        try {
            Set<String> entries = listZipEntries(zip);
            assertThat(entries).contains("src/test/java/com/example/TaskControllerTest.java");
        } finally {
            zip.delete();
        }
    }

    // ── Stage 6: secOpsArtifacts ──────────────────────────────────────────────

    @Test
    @DisplayName("secOpsArtifacts → report.md + Dockerfile + docker-compose.yml in ZIP")
    void packageResults_withSecOpsArtifacts_createsDockerFilesAndReport() throws IOException {
        ObjectNode secOps = objectMapper.createObjectNode();
        secOps.putArray("security_audit_report").add("No hardcoded secrets found.");
        secOps.put("Dockerfile", "FROM eclipse-temurin:21-jre\nENTRYPOINT [\"java\",\"-jar\",\"app.jar\"]");
        secOps.put("docker-compose.yml", "version: '3.8'\nservices:\n  app:\n    build: .");

        MidasContext ctx = MidasContext.start("Task app", "run-secops-001")
                .withSecOpsArtifacts(secOps);

        File zip = service.packageResults(ctx);
        try {
            Set<String> entries = listZipEntries(zip);
            assertThat(entries)
                    .contains("6_SecOps_Report.md")
                    .contains("Dockerfile")
                    .contains("docker-compose.yml");

            String report = readZipEntry(zip, "6_SecOps_Report.md");
            assertThat(report).contains("No hardcoded secrets found.");

            String dockerfile = readZipEntry(zip, "Dockerfile");
            assertThat(dockerfile).contains("eclipse-temurin:21-jre");
        } finally {
            zip.delete();
        }
    }

    @Test
    @DisplayName("productReviewReport → 7_ProductReview.md with verdict surfaced in the run report")
    void packageResults_withProductReview_containsReportAndVerdict() throws IOException {
        ObjectNode review = objectMapper.createObjectNode();
        review.put("verdict", "PASS_WITH_NOTES");
        review.put("summary", "Intent met; minor polish suggested.");
        ObjectNode remediation = objectMapper.createObjectNode();
        remediation.putArray("required_changes");
        remediation.putArray("recommendations").add("Add input debounce");
        review.set("remediation_block", remediation);
        review.putArray("coverage_matrix")
                .add(objectMapper.createObjectNode()
                        .put("requested_feature", "Create task")
                        .put("status", "COVERED")
                        .put("evidence", "implemented"));

        MidasContext ctx = MidasContext.start("Task app", "run-review-001")
                .withProductReviewReport(review);

        File zip = service.packageResults(ctx);
        try {
            Set<String> entries = listZipEntries(zip);
            assertThat(entries).contains("7_ProductReview.md");

            String report = readZipEntry(zip, "7_ProductReview.md");
            assertThat(report)
                    .contains("```json")
                    .contains("PASS_WITH_NOTES")
                    .contains("coverage_matrix");

            String runReport = readZipEntry(zip, "MIDAS_PIPELINE_REPORT.md");
            assertThat(runReport)
                    .contains("Product Review Verdict")
                    .contains("PASS_WITH_NOTES")
                    .contains("Intent met; minor polish suggested.")
                    .contains("Add input debounce");
        } finally {
            zip.delete();
        }
    }

    @Test
    @DisplayName("Skipped integration stage → no 3_IntegrationStrategy.md, bypass noted in run report")
    void packageResults_skippedIntegrationStrategy_notesBypassInReport() throws IOException {
        ObjectNode architecture = objectMapper.createObjectNode();
        architecture.put("has_external_integrations", false);
        architecture.put("database_type", "PG");

        MidasContext ctx = MidasContext.start("Self-contained app", "run-skip-int-001")
                .withTechnicalSpec(objectMapper.createObjectNode().put("business_goal", "Local only"))
                .withArchitectureDesign(architecture);

        File zip = service.packageResults(ctx);
        try {
            Set<String> entries = listZipEntries(zip);
            assertThat(entries)
                    .doesNotContain("3_IntegrationStrategy.md")
                    .contains("MIDAS_PIPELINE_REPORT.md");

            String runReport = readZipEntry(zip, "MIDAS_PIPELINE_REPORT.md");
            assertThat(runReport)
                    .contains("Integration Strategy")
                    .contains("dynamically bypassed");
        } finally {
            zip.delete();
        }
    }

    // ── Graceful degradation: coverage report ─────────────────────────────────

    @Test
    @DisplayName("coverageReport present → MIDAS_COVERAGE_REPORT.md with delivered capabilities, gaps, and matrix")
    void packageResults_withCoverageReport_writesCoverageMarkdown() throws IOException {
        ObjectNode report = objectMapper.createObjectNode();
        report.put("status", "COMPLETED_WITH_GAPS");
        report.put("build_verified", false);
        report.put("delivered_file_count", 2);
        report.putArray("delivered_capabilities").add("Create task").add("List tasks");
        report.putArray("gaps").add("Assign task not implemented");
        report.putArray("coverage_matrix")
                .add(objectMapper.createObjectNode().put("capability", "Create task").put("status", "GENERATED"))
                .add(objectMapper.createObjectNode().put("gap", "Assign task not implemented").put("status", "MISSING"));
        report.put("summary", "Delivered a best-effort partial artifact (2 file(s)).");

        MidasContext ctx = MidasContext.start("Task app", "run-coverage-001")
                .withCoverageReport(report);

        File zip = service.packageResults(ctx);
        try {
            Set<String> entries = listZipEntries(zip);
            assertThat(entries).contains("MIDAS_COVERAGE_REPORT.md");

            String md = readZipEntry(zip, "MIDAS_COVERAGE_REPORT.md");
            assertThat(md)
                    .contains("Delivered With Gaps")
                    .contains("COMPLETED_WITH_GAPS")
                    .contains("Create task")
                    .contains("Assign task not implemented")
                    .contains("GENERATED")
                    .contains("MISSING")
                    .contains("Delivered a best-effort partial artifact");
        } finally {
            zip.delete();
        }
    }

    @Test
    @DisplayName("No coverageReport (clean run) → no MIDAS_COVERAGE_REPORT.md in the ZIP")
    void packageResults_noCoverageReport_omitsCoverageMarkdown() throws IOException {
        MidasContext ctx = MidasContext.start("Clean run", "run-nocoverage-001");

        File zip = service.packageResults(ctx);
        try {
            assertThat(listZipEntries(zip)).doesNotContain("MIDAS_COVERAGE_REPORT.md");
        } finally {
            zip.delete();
        }
    }

    // ── Full context ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Fully populated context produces ZIP with all expected entry groups including product review")
    void packageResults_fullContext_allEntriesPresent() throws IOException {
        ObjectNode code = objectMapper.createObjectNode();
        code.put("Main.java", "class Main {}");

        ObjectNode tests = objectMapper.createObjectNode();
        tests.put("MainTest.java", "class MainTest {}");

        ObjectNode secOps = objectMapper.createObjectNode();
        secOps.putArray("security_audit_report").add("OK");
        secOps.put("Dockerfile", "FROM eclipse-temurin:21-jre");
        secOps.put("docker-compose.yml", "version: '3.8'");

        ObjectNode review = objectMapper.createObjectNode();
        review.put("verdict", "PASS");
        review.put("summary", "All features delivered.");
        review.putArray("coverage_matrix")
                .add(objectMapper.createObjectNode()
                        .put("requested_feature", "Core")
                        .put("status", "COVERED")
                        .put("evidence", "implemented"));
        ObjectNode remediation = objectMapper.createObjectNode();
        remediation.putArray("required_changes");
        remediation.putArray("recommendations");
        review.set("remediation_block", remediation);

        MidasContext ctx = MidasContext.start("Full pipeline", "run-full-001")
                .withTechnicalSpec(objectMapper.createObjectNode().put("business_goal", "Full"))
                .withArchitectureDesign(objectMapper.createObjectNode().put("database_type", "PG"))
                .withIntegrationStrategy(objectMapper.createObjectNode().put("parsing_logic", "none"))
                .withGeneratedSourceCode(code)
                .withGeneratedTests(tests)
                .withSecOpsArtifacts(secOps)
                .withProductReviewReport(review);

        File zip = service.packageResults(ctx);
        try {
            Set<String> entries = listZipEntries(zip);
            assertThat(entries)
                    .contains("README.md")
                    .contains("MIDAS_PIPELINE_REPORT.md")
                    .contains("1_SystemAnalysis.md")
                    .contains("2_Architecture.md")
                    .contains("3_IntegrationStrategy.md")
                    .contains("Main.java")
                    .contains("MainTest.java")
                    .contains("6_SecOps_Report.md")
                    .contains("7_ProductReview.md")
                    .contains("Dockerfile")
                    .contains("docker-compose.yml");
        } finally {
            zip.delete();
        }
    }

    // ── Browser-extension assembly (verbatim layout + placeholder icons + README) ─────

    @Test
    @DisplayName("Extension artifact: manifest stays at its load-root, missing PNG icons are backfilled, README has install/usage")
    void packageResults_extension_backfillsIconsAndSynthesizesReadme() throws IOException {
        ObjectNode code = objectMapper.createObjectNode();
        code.put("frontend/manifest.json", """
                {
                  "manifest_version": 3,
                  "name": "Semantic Saver",
                  "background": {"service_worker": "background.js"},
                  "icons": {"16": "images/icon16.png", "128": "images/icon128.png"},
                  "action": {"default_popup": "popup.html"}
                }
                """);
        code.put("frontend/background.js", "self.addEventListener('install', () => {});");
        code.put("frontend/popup.html", "<!DOCTYPE html><html><body></body></html>");

        MidasContext ctx = MidasContext.start("Save semantic page data", "run-ext-001")
                .withGeneratedSourceCode(code);

        File zip = service.packageResults(ctx);
        try {
            Set<String> entries = listZipEntries(zip);
            // Manifest preserved verbatim at its declared load-root — no src/ wrapper.
            assertThat(entries)
                    .contains("frontend/manifest.json")
                    .contains("frontend/background.js")
                    .doesNotContain("src/frontend/manifest.json");
            // Binary icons the model could not author are backfilled at the resolved paths.
            assertThat(entries)
                    .contains("frontend/images/icon16.png")
                    .contains("frontend/images/icon128.png");

            byte[] icon = readZipEntryBytes(zip, "frontend/images/icon16.png");
            assertThat(isValidPng(icon)).as("placeholder icon must be a valid PNG").isTrue();

            String readme = readZipEntry(zip, "README.md");
            assertThat(readme)
                    .contains("Load unpacked")
                    .contains("frontend")
                    .contains("Semantic Saver");
        } finally {
            zip.delete();
        }
    }

    // ── Cleanup verification ──────────────────────────────────────────────────

    @Test
    @DisplayName("ZIP is written to the durable artifact dir; transient staging dir is cleaned up")
    void packageResults_writesToArtifactDirAndCleansStaging() throws IOException {
        MidasContext ctx = MidasContext.start("Cleanup test", "run-cleanup-001");

        long stagingBefore = countTempDirEntries();
        File zip = service.packageResults(ctx);
        long stagingAfter  = countTempDirEntries();

        try {
            // The durable ZIP lives under the configured artifact dir, not the JVM temp dir.
            assertThat(zip).exists();
            assertThat(zip.getParentFile().toPath().toAbsolutePath().normalize())
                    .isEqualTo(artifactDir.toAbsolutePath().normalize());
            // The transient "midas_*" staging directory in java.io.tmpdir must be gone (no leak).
            assertThat(stagingAfter - stagingBefore).isLessThanOrEqualTo(0);
        } finally {
            zip.delete();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Set<String> listZipEntries(File zip) throws IOException {
        try (ZipFile zf = new ZipFile(zip)) {
            return zf.stream()
                     .map(ZipEntry::getName)
                     .collect(Collectors.toSet());
        }
    }

    private String readZipEntry(File zip, String entryName) throws IOException {
        return new String(readZipEntryBytes(zip, entryName), java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] readZipEntryBytes(File zip, String entryName) throws IOException {
        try (ZipFile zf = new ZipFile(zip)) {
            ZipEntry entry = zf.getEntry(entryName);
            assertThat(entry).as("Entry [%s] not found in ZIP", entryName).isNotNull();
            return zf.getInputStream(entry).readAllBytes();
        }
    }

    private boolean isValidPng(byte[] bytes) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        if (bytes.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (bytes[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private long countTempDirEntries() throws IOException {
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        try (var stream = java.nio.file.Files.list(tmpDir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith("midas_"))
                         .count();
        }
    }
}
