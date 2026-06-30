package com.midas.d3.validation;

import java.util.ArrayList;
import java.util.List;

/**
 * Small, dependency-free helpers for reasoning about the relative resource paths that web
 * descriptors (HTML {@code <script src>}, an MV3 {@code manifest.json}) use to reference sibling
 * files. Shared by the deterministic reference-consistency validators so they resolve a reference
 * to the same canonical source-map key the pipeline generated.
 *
 * <p>All paths are treated with {@code '/'} separators; {@code '\\'} is normalized away first so a
 * Windows-style generated key matches a POSIX-style reference.
 */
final class WebResourcePaths {

    private WebResourcePaths() {}

    /** Canonicalizes separators to {@code '/'} so map keys and references compare equal. */
    static String normalize(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    /** The directory portion of {@code path} including the trailing slash, or {@code ""} at root. */
    static String directoryOf(String path) {
        String normalized = normalize(path);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(0, slash + 1) : "";
    }

    /**
     * Resolves {@code ref} (as written inside a descriptor located in {@code baseDir}) to a path
     * relative to the project root, collapsing {@code .} and {@code ..} segments. A leading
     * {@code "/"} is treated as the descriptor's own root (the manifest/extension root), NOT a
     * filesystem absolute path — that is the web-extension convention. A {@code "://"} URL is
     * returned normalized and is expected to be skipped by callers (it is not a local file).
     */
    static String resolveRelative(String baseDir, String ref) {
        String normalizedRef = normalize(ref);
        if (normalizedRef.contains("://")) {
            return normalizedRef;
        }
        // Strip a leading "/" or "./" — both denote a path relative to the descriptor root.
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

    /** True when {@code ref} points at an external URL rather than a local bundled file. */
    static boolean isExternal(String ref) {
        String normalized = normalize(ref);
        return normalized.contains("://") || normalized.startsWith("//");
    }
}
