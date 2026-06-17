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
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Service that collects all validated pipeline artifacts from a {@link MidasContext}
 * and packages them into a single ZIP archive for delivery or download.
 *
 * <h2>Archive structure</h2>
 * <pre>
 *   midas_result_20260614_1200_&lt;runId&gt;.zip
 *   ├── README.md                          ← always present: run metadata
 *   ├── 1_SystemAnalysis.md                ← Agent 1: technical specification
 *   ├── 2_Architecture.md                  ← Agent 2: database schema + REST contracts
 *   ├── 3_IntegrationStrategy.md           ← Agent 3: external integrations
 *   ├── src/
 *   │   └── …/File.java                    ← Agent 4: generated source files (verbatim paths)
 *   ├── tests/
 *   │   └── …/FileTest.java                ← Agent 5: generated test files (verbatim paths)
 *   ├── 6_SecOps_Report.md                 ← Agent 6: security audit findings
 *   ├── Dockerfile                         ← Agent 6: production Dockerfile
 *   └── docker-compose.yml                 ← Agent 6: docker-compose manifest
 * </pre>
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
        writeRunMetadata(ctx, dir);

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
        if (ctx.getGeneratedSourceCode() != null) {
            writeSourceFileMap(dir.resolve("src"), ctx.getGeneratedSourceCode());
        }
        if (ctx.getGeneratedTests() != null) {
            writeSourceFileMap(dir.resolve("tests"), ctx.getGeneratedTests());
        }
        if (ctx.getSecOpsArtifacts() != null) {
            writeSecOpsArtifacts(dir, ctx.getSecOpsArtifacts());
        }
    }

    /**
     * Always-present README with run metadata so the archive is never completely empty.
     */
    private void writeRunMetadata(MidasContext ctx, Path dir) throws IOException {
        String content = String.format("""
                # MIDAS Pipeline Run Report

                | Field | Value |
                |---|---|
                | **Run ID** | `%s` |
                | **Created** | `%s` |
                | **Pipeline** | MIDAS D3 Software Pipeline |

                ## Original Idea

                > %s
                """,
                ctx.getPipelineRunId(),
                ctx.getCreatedAt(),
                ctx.getRawUserIdea().replace("\n", "\n> "));
        Files.writeString(dir.resolve("README.md"), content, StandardCharsets.UTF_8);
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
            // Normalize path separators for the current OS
            String relativePath = entry.getKey().replace('/', File.separatorChar);
            Path   target       = baseDir.resolve(relativePath).normalize();

            // Guard against path-traversal
            if (!target.startsWith(baseDir)) {
                log.warn("[ArtifactPackagingService] Skipping suspicious path: {}", entry.getKey());
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

    /** Strips characters unsafe for use in file/directory names. */
    private static String sanitize(String id) {
        return id != null ? id.replaceAll("[^a-zA-Z0-9\\-_]", "_") : "unknown";
    }
}
