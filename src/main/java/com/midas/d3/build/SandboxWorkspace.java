package com.midas.d3.build;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * it is safe to use in a try-with-resources block. Path entries are confined to the
 * workspace root (a {@code ../} traversal attempt is rejected) so a malformed or hostile
 * generated path can never write outside the sandbox.
 */
@Slf4j
public final class SandboxWorkspace implements AutoCloseable {

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
     * Writes every {@code path → textual contents} entry of {@code sourceMap} into the
     * workspace, creating parent directories as needed.
     *
     * @return the number of files written
     */
    public int materialize(JsonNode sourceMap) {
        Objects.requireNonNull(sourceMap, "sourceMap must not be null");
        if (!sourceMap.isObject()) {
            return 0;
        }
        int written = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = sourceMap.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode value = entry.getValue();
            if (value == null || !value.isTextual()) {
                continue;
            }
            writeFile(entry.getKey(), value.asText());
            written++;
        }
        log.debug("[SandboxWorkspace] Materialized {} file(s) into {}", written, root);
        return written;
    }

    private void writeFile(String relativePath, String contents) {
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Refusing to write outside sandbox root: [" + relativePath + "]");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, contents, StandardCharsets.UTF_8);
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
