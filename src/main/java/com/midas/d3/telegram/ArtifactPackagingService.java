package com.midas.d3.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.context.MidasContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Service that collects all validated pipeline artifacts from a {@link MidasContext}
 * and packages them into a single ZIP archive for delivery or download.
 *
 * <h2>Archive structure</h2>
 * <pre>
 *   midas_result_20260614_1200_&lt;runId&gt;.zip
 *   ├── README.md                          ← user-facing install/usage (synthesized if the
 *   │                                         project did not emit its own)
 *   ├── MIDAS_PIPELINE_REPORT.md           ← always present: run metadata + Controller verdict
 *   ├── 1_SystemAnalysis.md                ← Agent 1: technical specification
 *   ├── 2_Architecture.md                  ← Agent 2: database schema + REST contracts
 *   ├── 3_IntegrationStrategy.md           ← Agent 3: external integrations
 *   ├── &lt;verbatim source paths&gt;            ← Agent 4: generated source files at their real paths
 *   │                                         (e.g. frontend/manifest.json, backend/src/main/...)
 *   ├── &lt;verbatim test paths&gt;              ← Agent 5: generated test files at their real paths
 *   ├── &lt;placeholder icons&gt;                ← valid PNGs backfilled for any manifest icon ref the
 *   │                                         model could not author (binary)
 *   ├── 6_SecOps_Report.md                 ← Agent 6: security audit findings
 *   ├── 7_ProductReview.md                 ← Agent 7: Controller quality-gate report
 *   ├── Dockerfile                         ← Agent 6: production Dockerfile
 *   └── docker-compose.yml                 ← Agent 6: docker-compose manifest
 * </pre>
 *
 * <h2>Verbatim layout (Assembler discipline)</h2>
 * Source and test maps are written at their <em>exact</em> generated paths — the same tree the
 * build sandbox compiled — rather than under a synthetic {@code src/}/{@code tests/} wrapper. A
 * browser extension loads from the directory holding {@code manifest.json}; a stray wrapper segment
 * breaks every relative reference inside it, so the delivered artifact must mirror what was built.
 *
 * <h2>Lifecycle</h2>
 * A temporary directory is created, populated, zipped, and then deleted — all within
 * one {@link #packageResults} call. Only the returned ZIP file remains on disk; the
 * caller is responsible for deleting it after use (typically in a {@code finally} block).
 *
 * <h2>Error handling</h2>
 * All {@link IOException}s from file I/O propagate to the caller as checked exceptions
 * so they can be caught and surfaced to the user via Telegram.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArtifactPackagingService {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmm").withZone(ZoneOffset.UTC);

    /** A valid 1×1 PNG used as the placeholder for any manifest icon reference the model could not emit. */
    private static final byte[] PLACEHOLDER_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    private final ObjectMapper objectMapper;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Packages all available artifacts from {@code ctx} into a ZIP archive.
     *
     * @param ctx the completed pipeline context; may have null artifact fields
     *            for stages that were skipped or retried to failure
     * @return a {@link File} pointing to the created ZIP archive on disk
     * @throws IOException if any file-system operation fails (temp dir creation,
     *                     file writing, ZIP creation)
     */
    public File packageResults(MidasContext ctx) throws IOException {
        String runId  = sanitize(ctx.getPipelineRunId());
        Path   tmpDir = Files.createTempDirectory("midas_" + runId + "_");

        log.info("[ArtifactPackagingService] Packaging artifacts for run [{}] in [{}].",
                ctx.getPipelineRunId(), tmpDir);

        try {
            writeAllArtifacts(ctx, tmpDir);
            File zip = createZip(tmpDir, ctx);
            log.info("[ArtifactPackagingService] ZIP created: {} ({} bytes).",
                    zip.getName(), zip.length());
            return zip;
        } catch (UncheckedIOException e) {
            throw new IOException("Artifact ZIP creation failed: " + e.getMessage(), e.getCause());
        } finally {
            deleteRecursively(tmpDir);
        }
    }

    // ── Artifact writers ──────────────────────────────────────────────────────

    private void writeAllArtifacts(MidasContext ctx, Path dir) throws IOException {
        // Source and tests first, at verbatim paths, so the delivered tree mirrors what was built.
        if (ctx.getGeneratedSourceCode() != null) {
            writeSourceFileMap(dir, ctx.getGeneratedSourceCode());
        }
        if (ctx.getGeneratedTests() != null) {
            writeSourceFileMap(dir, ctx.getGeneratedTests());
        }

        writePipelineReport(ctx, dir);

        // Graceful degradation: an honest, client-facing account of what was delivered vs. what could
        // not be completed. Present only on a COMPLETED_WITH_GAPS run (set by DegradeToGapsAction).
        if (ctx.getCoverageReport() != null) {
            writeCoverageReport(dir, ctx.getCoverageReport());
        }

        if (ctx.getTechnicalSpec() != null) {
            writeJsonAsMarkdown(dir, "1_SystemAnalysis.md",
                    "System Analysis — Technical Specification", ctx.getTechnicalSpec());
        }
        if (ctx.getArchitectureDesign() != null) {
            writeJsonAsMarkdown(dir, "2_Architecture.md",
                    "Architecture Design — Database Schema & REST Contracts", ctx.getArchitectureDesign());
        }
        if (ctx.getIntegrationStrategy() != null) {
            writeJsonAsMarkdown(dir, "3_IntegrationStrategy.md",
                    "Integration Strategy — External Services & Rate Limiting", ctx.getIntegrationStrategy());
        }
        if (ctx.getSecOpsArtifacts() != null) {
            writeSecOpsArtifacts(dir, ctx.getSecOpsArtifacts());
        }
        if (ctx.getProductReviewReport() != null) {
            writeJsonAsMarkdown(dir, "7_ProductReview.md",
                    "Product Review — Controller Quality Gate", ctx.getProductReviewReport());
        }

        // Deterministic backfill: binary icons the model could not author, and a user-facing
        // install/usage README computed from the real load-root.
        backfillPlaceholderIcons(dir, ctx.getGeneratedSourceCode());
        ensureUserReadme(ctx, dir);
    }

    /**
     * Always-present run report with metadata and Controller verdict. Lives in its own file
     * (not README.md) so it can never masquerade as the project's user documentation and never
     * clobbers a README the project itself generated.
     */
    private void writePipelineReport(MidasContext ctx, Path dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# MIDAS Pipeline Run Report\n\n");
        sb.append("| Field | Value |\n|---|---|\n");
        sb.append("| **Run ID** | `").append(ctx.getPipelineRunId()).append("` |\n");
        sb.append("| **Created** | `").append(ctx.getCreatedAt()).append("` |\n");
        sb.append("| **Pipeline** | MIDAS D3 Software Pipeline |\n\n");

        JsonNode review = ctx.getProductReviewReport();
        if (review != null) {
            sb.append("## Product Review Verdict\n\n");
            JsonNode verdictNode = review.get("verdict");
            if (verdictNode != null && verdictNode.isTextual() && !verdictNode.asText().isBlank()) {
                sb.append("| **Verdict** | **").append(verdictNode.asText().strip()).append("** |\n\n");
            }
            JsonNode summary = review.get("summary");
            if (summary != null && summary.isTextual() && !summary.asText().isBlank()) {
                sb.append("**Summary:** ").append(summary.asText().strip()).append("\n\n");
            }
            appendProductReviewNotes(sb, review);
        }

        if (isIntegrationStageSkipped(ctx)) {
            sb.append("## Pipeline Routing\n\n");
            sb.append("The **Integration Strategy** stage was dynamically bypassed ");
            sb.append("(no external integrations required for this product).\n\n");
        }

        sb.append("## Original Idea\n\n");
        sb.append("> ").append(ctx.getRawUserIdea().replace("\n", "\n> ")).append("\n");

        Files.writeString(dir.resolve("MIDAS_PIPELINE_REPORT.md"), sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Renders the graceful-degradation coverage report ({@link MidasContext#getCoverageReport()}) as a
     * human-readable {@code MIDAS_COVERAGE_REPORT.md}. Reads the {@code DegradeToGapsAction}-shaped node
     * defensively — any missing section is simply skipped so a partially-populated report never fails
     * packaging (the whole point of degradation is to always deliver something).
     */
    private void writeCoverageReport(Path dir, JsonNode report) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# MIDAS Coverage Report — Delivered With Gaps\n\n");
        sb.append("> **This project was delivered as a best-effort partial artifact.** ");
        sb.append("The build was **not** verified, and the gaps listed below could not be completed ");
        sb.append("within the generation budget. Review the code and the gaps before use.\n\n");

        sb.append("| Field | Value |\n|---|---|\n");
        sb.append("| **Status** | `").append(textOr(report, "status", "COMPLETED_WITH_GAPS")).append("` |\n");
        sb.append("| **Build verified** | `").append(boolText(report.get("build_verified"), false)).append("` |\n");
        JsonNode fileCount = report.get("delivered_file_count");
        if (fileCount != null && fileCount.isNumber()) {
            sb.append("| **Delivered files** | `").append(fileCount.asInt()).append("` |\n");
        }
        sb.append("\n");

        appendBulletList(sb, "## Delivered capabilities", report.get("delivered_capabilities"));
        appendBulletList(sb, "## Gaps (could not be completed)", report.get("gaps"));

        JsonNode matrix = report.get("coverage_matrix");
        if (matrix != null && matrix.isArray() && !matrix.isEmpty()) {
            sb.append("## Coverage matrix\n\n");
            sb.append("| Item | Status |\n|---|---|\n");
            for (JsonNode row : matrix) {
                JsonNode item = row.get("capability") != null ? row.get("capability") : row.get("gap");
                String itemText = item != null && item.isTextual() ? item.asText().strip() : "—";
                sb.append("| ").append(itemText).append(" | `")
                  .append(textOr(row, "status", "UNKNOWN")).append("` |\n");
            }
            sb.append("\n");
        }

        JsonNode summary = report.get("summary");
        if (summary != null && summary.isTextual() && !summary.asText().isBlank()) {
            sb.append("## Summary\n\n").append(summary.asText().strip()).append("\n");
        }

        Files.writeString(dir.resolve("MIDAS_COVERAGE_REPORT.md"), sb.toString(), StandardCharsets.UTF_8);
    }

    private void appendBulletList(StringBuilder sb, String heading, JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return;
        }
        sb.append(heading).append("\n\n");
        for (JsonNode item : array) {
            if (item.isTextual() && !item.asText().isBlank()) {
                sb.append("- ").append(item.asText().strip()).append("\n");
            }
        }
        sb.append("\n");
    }

    private static String textOr(JsonNode parent, String field, String fallback) {
        JsonNode node = parent.get(field);
        return (node != null && node.isTextual() && !node.asText().isBlank()) ? node.asText().strip() : fallback;
    }

    private static String boolText(JsonNode node, boolean fallback) {
        return String.valueOf(node != null && node.isBoolean() ? node.asBoolean() : fallback);
    }

    private void appendProductReviewNotes(StringBuilder sb, JsonNode report) {
        JsonNode block = report.get("remediation_block");
        if (block == null || !block.isObject()) {
            return;
        }
        JsonNode recommendations = block.get("recommendations");
        if (recommendations == null || !recommendations.isArray() || recommendations.isEmpty()) {
            return;
        }
        sb.append("### Notes & Recommendations\n\n");
        for (JsonNode item : recommendations) {
            if (item.isTextual() && !item.asText().isBlank()) {
                sb.append("- ").append(item.asText().strip()).append("\n");
            }
        }
        sb.append("\n");
    }

    private boolean isIntegrationStageSkipped(MidasContext ctx) {
        if (ctx.getIntegrationStrategy() != null) {
            return false;
        }
        JsonNode architecture = ctx.getArchitectureDesign();
        if (architecture == null) {
            return false;
        }
        JsonNode flag = architecture.get("has_external_integrations");
        return flag != null && flag.isBoolean() && !flag.asBoolean();
    }

    /**
     * Writes a {@link JsonNode} as a fenced-code-block Markdown file.
     */
    private void writeJsonAsMarkdown(Path dir, String filename, String title, JsonNode node)
            throws IOException {
        String prettyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        String content = "# " + title + "\n\n```json\n" + prettyJson + "\n```\n";
        Files.writeString(dir.resolve(filename), content, StandardCharsets.UTF_8);
    }

    /**
     * Writes a JSON map of {@code { "path/File.java" : "source code" }} entries as
     * individual files under {@code baseDir}, preserving subdirectory structure.
     */
    private void writeSourceFileMap(Path baseDir, JsonNode fileMap) throws IOException {
        Files.createDirectories(baseDir);
        Iterator<Map.Entry<String, JsonNode>> fields = fileMap.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            Path target = safeResolve(baseDir, entry.getKey());
            if (target == null) {
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, entry.getValue().asText(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Writes SecOps artifacts: security report as Markdown, Dockerfile and
     * docker-compose.yml verbatim.
     */
    private void writeSecOpsArtifacts(Path dir, JsonNode secOps) throws IOException {
        JsonNode auditReport = secOps.get("security_audit_report");
        if (auditReport != null && auditReport.isArray()) {
            StringBuilder sb = new StringBuilder("# SecOps — Security Audit Report\n\n");
            for (JsonNode finding : auditReport) {
                sb.append("- ").append(finding.asText()).append("\n");
            }
            Files.writeString(dir.resolve("6_SecOps_Report.md"), sb.toString(), StandardCharsets.UTF_8);
        }

        // v2 schema: a runtime-appropriate map of release artifacts (web-store zip steps,
        // deploy notes, or — for CONTAINERIZED — a Dockerfile/compose). Names may contain
        // path separators, so reuse the path-traversal-safe writer.
        JsonNode releaseArtifacts = secOps.get("release_artifacts");
        if (releaseArtifacts != null && releaseArtifacts.isObject()) {
            writeSourceFileMap(dir, releaseArtifacts);
        }

        // Legacy schema: top-level Dockerfile / docker-compose.yml.
        writeTextNodeIfPresent(dir, secOps, "Dockerfile");
        writeTextNodeIfPresent(dir, secOps, "docker-compose.yml");
    }

    private void writeTextNodeIfPresent(Path dir, JsonNode parent, String fieldName)
            throws IOException {
        JsonNode node = parent.get(fieldName);
        if (node != null && !node.asText().isBlank()) {
            Files.writeString(dir.resolve(fieldName), node.asText(), StandardCharsets.UTF_8);
        }
    }

    // ── Deterministic backfill (Assembler) ──────────────────────────────────────

    /**
     * For every PNG icon an extension manifest references but the model did not (could not) emit,
     * writes a valid placeholder PNG at the resolved path so the reference resolves and the
     * extension loads. Binary assembly belongs in Java, never the LLM.
     */
    private void backfillPlaceholderIcons(Path dir, JsonNode sourceFiles) throws IOException {
        if (sourceFiles == null || !sourceFiles.isObject()) {
            return;
        }
        Set<String> existing = sourcePathSet(sourceFiles);
        Set<String> written = new LinkedHashSet<>();

        for (Map.Entry<String, JsonNode> entry : iterable(sourceFiles)) {
            String path = normalize(entry.getKey());
            if (!entry.getValue().isTextual() || !isManifestPath(path)) {
                continue;
            }
            JsonNode manifest = tryParse(entry.getValue().asText());
            if (manifest == null || !manifest.isObject()) {
                continue;
            }
            String manifestDir = directoryOf(path);
            for (String iconRef : collectIconPngRefs(manifest)) {
                String resolved = resolveRelative(manifestDir, iconRef);
                if (resolved.isBlank() || existing.contains(resolved) || !written.add(resolved)) {
                    continue;
                }
                Path target = safeResolve(dir, resolved);
                if (target == null) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.write(target, PLACEHOLDER_PNG);
                log.info("[ArtifactPackagingService] Backfilled placeholder icon [{}] referenced by [{}].",
                        resolved, path);
            }
        }
    }

    /**
     * Synthesizes a user-facing {@code README.md} with install/usage instructions computed from the
     * real load-root, unless the project already emitted its own README (which is then preserved).
     */
    private void ensureUserReadme(MidasContext ctx, Path dir) throws IOException {
        JsonNode sourceFiles = ctx.getGeneratedSourceCode();
        if (sourceFiles == null || !sourceFiles.isObject() || sourceFiles.isEmpty()) {
            return; // No project artifact to document.
        }
        if (projectHasOwnReadme(sourceFiles)) {
            return; // Respect the project's own README.
        }

        Optional<ManifestLocation> extension = findExtensionManifest(sourceFiles);
        Set<String> paths = sourcePathSet(sourceFiles);

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(projectTitle(ctx, extension.orElse(null))).append("\n\n");

        if (extension.isPresent()) {
            String loadRoot = extension.get().directory();
            String loadRootLabel = loadRoot.isBlank() ? "the archive root" : "`" + loadRoot + "`";
            sb.append("## Installation (Chrome / Edge)\n\n");
            sb.append("1. Open `chrome://extensions` (or `edge://extensions`).\n");
            sb.append("2. Enable **Developer mode** (top-right toggle).\n");
            sb.append("3. Click **Load unpacked**.\n");
            sb.append("4. Select the ").append(loadRootLabel)
              .append(" directory from this archive (the folder that contains `manifest.json`).\n\n");
        } else if (containsFile(paths, "package.json")) {
            sb.append("## Installation\n\n");
            sb.append("```sh\nnpm install\nnpm start\n```\n\n");
        } else if (containsFile(paths, "pom.xml")) {
            sb.append("## Build & Run\n\n");
            sb.append("```sh\nmvn clean package\njava -jar target/*.jar\n```\n\n");
        } else {
            sb.append("## Contents\n\n");
            sb.append("This archive contains the generated project source. ");
            sb.append("See `MIDAS_PIPELINE_REPORT.md` for the build report.\n\n");
        }

        sb.append("## What this does\n\n");
        sb.append("> ").append(ctx.getRawUserIdea().replace("\n", "\n> ")).append("\n\n");
        sb.append("---\n_Generated by the MIDAS pipeline. See `MIDAS_PIPELINE_REPORT.md` for the run report._\n");

        Files.writeString(dir.resolve("README.md"), sb.toString(), StandardCharsets.UTF_8);
    }

    private String projectTitle(MidasContext ctx, ManifestLocation extension) {
        if (extension != null) {
            JsonNode name = extension.manifest().get("name");
            if (name != null && name.isTextual() && !name.asText().isBlank()) {
                return name.asText().strip();
            }
        }
        JsonNode spec = ctx.getTechnicalSpec();
        if (spec != null) {
            JsonNode goal = spec.get("business_goal");
            if (goal != null && goal.isTextual() && !goal.asText().isBlank()) {
                return goal.asText().strip();
            }
        }
        return "Generated Project";
    }

    private boolean projectHasOwnReadme(JsonNode sourceFiles) {
        for (Map.Entry<String, JsonNode> entry : iterable(sourceFiles)) {
            String name = fileName(normalize(entry.getKey())).toLowerCase(Locale.ROOT);
            if (name.equals("readme.md") || name.equals("readme")) {
                return true;
            }
        }
        return false;
    }

    /** Finds the shallowest extension manifest (the natural load-root) in the source map. */
    private Optional<ManifestLocation> findExtensionManifest(JsonNode sourceFiles) {
        ManifestLocation best = null;
        for (Map.Entry<String, JsonNode> entry : iterable(sourceFiles)) {
            String path = normalize(entry.getKey());
            if (!entry.getValue().isTextual() || !isManifestPath(path)) {
                continue;
            }
            JsonNode manifest = tryParse(entry.getValue().asText());
            if (manifest == null || !looksLikeExtensionManifest(manifest)) {
                continue;
            }
            String dir = directoryOf(path);
            if (best == null || dir.length() < best.directory().length()) {
                best = new ManifestLocation(dir, manifest);
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean looksLikeExtensionManifest(JsonNode manifest) {
        return manifest.isObject() && (manifest.has("manifest_version")
                || manifest.has("background") || manifest.has("content_scripts")
                || manifest.has("action") || manifest.has("browser_action"));
    }

    private List<String> collectIconPngRefs(JsonNode manifest) {
        List<String> refs = new ArrayList<>();
        addIconValues(refs, manifest.get("icons"));
        addDefaultIcon(refs, manifest.get("action"));
        addDefaultIcon(refs, manifest.get("browser_action"));
        addDefaultIcon(refs, manifest.get("page_action"));
        return refs;
    }

    private void addDefaultIcon(List<String> refs, JsonNode actionNode) {
        if (actionNode != null && actionNode.isObject()) {
            JsonNode icon = actionNode.get("default_icon");
            if (icon != null && icon.isTextual()) {
                addPng(refs, icon.asText());
            } else {
                addIconValues(refs, icon);
            }
        }
    }

    private void addIconValues(List<String> refs, JsonNode iconMap) {
        if (iconMap != null && iconMap.isObject()) {
            iconMap.fields().forEachRemaining(e -> {
                if (e.getValue().isTextual()) {
                    addPng(refs, e.getValue().asText());
                }
            });
        }
    }

    private void addPng(List<String> refs, String ref) {
        if (ref != null && ref.strip().toLowerCase(Locale.ROOT).endsWith(".png")) {
            refs.add(ref.strip());
        }
    }

    // ── ZIP creation ──────────────────────────────────────────────────────────

    private File createZip(Path sourceDir, MidasContext ctx) throws IOException {
        String timestamp = TIMESTAMP_FMT.format(Instant.now());
        String runId     = sanitize(ctx.getPipelineRunId());
        // createTempFile guarantees uniqueness even under concurrent runs
        Path zipPath = Files.createTempFile("midas_result_" + timestamp + "_" + runId + "_", ".zip");

        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(zipPath)))) {
            Files.walk(sourceDir)
                    .filter(p -> !Files.isDirectory(p))
                    .forEach(p -> addEntryToZip(zos, sourceDir, p));
        }
        return zipPath.toFile();
    }

    private void addEntryToZip(ZipOutputStream zos, Path baseDir, Path file) {
        try {
            // Always use '/' as separator inside the ZIP (cross-platform)
            String entryName = baseDir.relativize(file).toString()
                    .replace(File.separatorChar, '/');
            zos.putNextEntry(new ZipEntry(entryName));
            Files.copy(file, zos);
            zos.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to zip entry [" + file + "]: " + e.getMessage(), e);
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private void deleteRecursively(Path dir) {
        try {
            if (!Files.exists(dir)) return;
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            log.warn("[ArtifactPackagingService] Could not delete temp path [{}]: {}",
                                    p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("[ArtifactPackagingService] Error during temp dir cleanup [{}]: {}",
                    dir, e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves {@code relativePath} under {@code baseDir}, guarding against path traversal.
     * Returns {@code null} (logged) when the entry would escape the base directory.
     */
    private Path safeResolve(Path baseDir, String relativePath) {
        String normalized = relativePath.replace('/', File.separatorChar);
        Path target = baseDir.resolve(normalized).normalize();
        if (!target.startsWith(baseDir)) {
            log.warn("[ArtifactPackagingService] Skipping suspicious path: {}", relativePath);
            return null;
        }
        return target;
    }

    private Set<String> sourcePathSet(JsonNode sourceFiles) {
        Set<String> paths = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> entry : iterable(sourceFiles)) {
            if (entry.getValue().isTextual()) {
                paths.add(normalize(entry.getKey()));
            }
        }
        return paths;
    }

    private JsonNode tryParse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException e) {
            return null;
        }
    }

    private Iterable<Map.Entry<String, JsonNode>> iterable(JsonNode objectNode) {
        return objectNode::fields;
    }

    private static boolean isManifestPath(String normalizedPath) {
        return normalizedPath.equals("manifest.json") || normalizedPath.endsWith("/manifest.json");
    }

    private static boolean containsFile(Set<String> paths, String fileName) {
        for (String p : paths) {
            if (fileName(p).equalsIgnoreCase(fileName)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private static String fileName(String path) {
        String normalized = normalize(path);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static String directoryOf(String path) {
        String normalized = normalize(path);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(0, slash + 1) : "";
    }

    private static String resolveRelative(String baseDir, String ref) {
        String normalizedRef = normalize(ref);
        while (normalizedRef.startsWith("/") || normalizedRef.startsWith("./")) {
            normalizedRef = normalizedRef.startsWith("./")
                    ? normalizedRef.substring(2)
                    : normalizedRef.substring(1);
        }
        String combined = normalize(baseDir) + normalizedRef;
        String[] parts = combined.split("/");
        List<String> resolved = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!resolved.isEmpty()) {
                    resolved.remove(resolved.size() - 1);
                }
            } else {
                resolved.add(part);
            }
        }
        return String.join("/", resolved);
    }

    /** Strips characters unsafe for use in file/directory names. */
    private static String sanitize(String id) {
        return id != null ? id.replaceAll("[^a-zA-Z0-9\\-_]", "_") : "unknown";
    }

    private record ManifestLocation(String directory, JsonNode manifest) {}
}
