package com.midas.d3.build;

import java.util.Objects;

/**
 * One detected verifiable surface of a generated project: a {@link ProjectKind} rooted at a
 * relative directory within the source map.
 *
 * @param kind    what the surface is and how it must be verified
 * @param rootDir the directory holding the surface's descriptor, with a trailing {@code '/'} (or
 *                {@code ""} for the project root). Toolchain builds run from this directory; the
 *                structural verifier resolves references relative to it.
 */
public record BuildSurface(ProjectKind kind, String rootDir) {

    public BuildSurface {
        Objects.requireNonNull(kind, "kind must not be null");
        rootDir = rootDir == null ? "" : rootDir;
    }

    /** A human-readable label for logs and report summaries, e.g. {@code MAVEN@backend/}. */
    public String label() {
        return kind + "@" + (rootDir.isEmpty() ? "<root>" : rootDir);
    }
}
