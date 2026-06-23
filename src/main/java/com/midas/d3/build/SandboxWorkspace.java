package com.midas.d3.build;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * A throwaway on-disk materialization of a generated source map ({@code path → contents}),
 * used to give a {@link BuildExecutor} a real project tree to compile.
 *
 * <p>Created under the system temp directory and deleted in full on {@link #close()} — so
 * it is safe to use in a try-with-resources block.
 *
 * <h2>Containment</h2>
 * Every entry is confined to the workspace root: a {@code ../} traversal, an absolute path, or a
 * syntactically invalid path is <b>skipped</b> (logged, not written) so a hostile or malformed
 * generated path can never write outside the sandbox — and one bad entry never wedges the whole
 * materialization. Disk-DoS is bounded by {@link #MAX_FILES} and {@link #MAX_TOTAL_BYTES}.
 */
@Slf4j
public final class SandboxWorkspace implements AutoCloseable {

    /** Cap on the number of files materialized — bounds an inode/file-count DoS. */
    public static final int MAX_FILES = 5_000;

    /** Cap on total bytes materialized — bounds a disk-fill DoS. */
    public static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;

    private final Path root;

    private SandboxWorkspace(Path root) {
        this.root = root;
    }

    /** Creates an empty workspace under a uniquely-named temp directory. */
    public static SandboxWorkspace create(String runId) throws IOException {
        String safeRun = (runId == null || runId.isBlank()) ? "run" : runId.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path dir = Files.createTempDirectory("midas-build-" + safeRun + "-");
        log.debug("[SandboxWorkspace] Created sandbox at {}", dir);
        return new SandboxWorkspace(dir);
    }

    public Path root() {
        return root;
    }

    /**
     * Writes every {@code path → textual contents} entry of {@code sourceMap} into the workspace,
     * creating parent directories as needed. Entries that would escape the root, are absolute, are
     * syntactically invalid, or would breach {@link #MAX_FILES} / {@link #MAX_TOTAL_BYTES} are
     * skipped (and logged) rather than aborting the materialization.
     *
     * @return the number of files actually written
     */
    public int materialize(JsonNode sourceMap) {
        Objects.requireNonNull(sourceMap, "sourceMap must not be null");
        if (!sourceMap.isObject()) {
            return 0;
        }
        int written = 0;
        long totalBytes = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = sourceMap.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode value = entry.getValue();
            if (value == null || !value.isTextual()) {
                continue;
            }
            if (written >= MAX_FILES) {
                log.warn("[SandboxWorkspace] File cap ({}) reached — refusing further files.", MAX_FILES);
                break;
            }
            String contents = value.asText();
            long size = contents.getBytes(StandardCharsets.UTF_8).length;
            if (totalBytes + size > MAX_TOTAL_BYTES) {
                log.warn("[SandboxWorkspace] Total-size cap ({} bytes) would be exceeded — stopping.", MAX_TOTAL_BYTES);
                break;
            }
            if (writeFile(entry.getKey(), contents)) {
                written++;
                totalBytes += size;
            }
        }
        log.debug("[SandboxWorkspace] Materialized {} file(s) ({} bytes) into {}", written, totalBytes, root);
        return written;
    }

    /** @return true if the file was written; false if the path was rejected for containment. */
    private boolean writeFile(String relativePath, String contents) {
        Path target;
        try {
            target = root.resolve(relativePath).normalize();
        } catch (InvalidPathException e) {
            log.warn("[SandboxWorkspace] Skipping syntactically invalid path [{}]: {}", relativePath, e.getMessage());
            return false;
        }
        // Confinement: an absolute path or a `../` traversal normalizes to somewhere outside root
        // (Path.startsWith is component-wise, so a sibling like `root-evil` does not match).
        if (target.equals(root) || !target.startsWith(root)) {
            log.warn("[SandboxWorkspace] Refusing path outside sandbox — skipping hostile path [{}]", relativePath);
            return false;
        }
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, contents, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to materialize file [" + relativePath + "]", e);
        }
    }

    @Override
    public void close() {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("[SandboxWorkspace] Could not delete {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("[SandboxWorkspace] Cleanup of {} failed: {}", root, e.getMessage());
        }
    }
}
