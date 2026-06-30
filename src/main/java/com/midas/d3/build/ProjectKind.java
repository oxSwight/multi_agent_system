package com.midas.d3.build;

/**
 * A buildable/verifiable <b>surface</b> kind within a generated project. Unlike {@link BuildTool}
 * (which names only a JVM/Node <em>toolchain</em>), a {@code ProjectKind} classifies what a surface
 * <em>is</em> and how it must be verified — including client surfaces that have no compiler at all.
 *
 * <h2>Why this exists</h2>
 * The old detector returned a single {@link BuildTool} on a first-match basis, so a hybrid project
 * (backend Maven + frontend extension) was verified only on the backend, and a pure-JS extension
 * (no {@code package.json}) resolved to {@link BuildTool#NONE} and was skipped fail-open. Surfaces
 * let every part of a project be detected and verified on its own terms.
 *
 * <h2>Verification mode</h2>
 * A surface is either backed by a {@linkplain #isToolchain() toolchain} (built via a
 * {@link BuildExecutor}) or verified <b>structurally</b> — a deterministic, offline, toolchain-free
 * check (e.g. an MV3 extension: the manifest parses, declares {@code manifest_version: 3}, and every
 * code file it references exists). Structural verification is the "compiler, not oracle" gate for
 * surfaces that otherwise have nothing to compile.
 */
public enum ProjectKind {

    MAVEN(BuildTool.MAVEN),
    GRADLE(BuildTool.GRADLE),
    NODE_NPM(BuildTool.NPM),
    /** A Manifest V3 browser extension — verified structurally (no toolchain). */
    CHROME_EXTENSION_MV3(BuildTool.NONE);

    private final BuildTool toolchain;

    ProjectKind(BuildTool toolchain) {
        this.toolchain = toolchain;
    }

    /** The build toolchain backing this surface, or {@link BuildTool#NONE} for structural surfaces. */
    public BuildTool toolchain() {
        return toolchain;
    }

    /** True when this surface is built by a real {@link BuildExecutor}; false for structural surfaces. */
    public boolean isToolchain() {
        return toolchain != BuildTool.NONE;
    }
}
