package com.midas.d3.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.validation.ManifestReferenceValidator;

import java.util.List;

/**
 * Deterministic, toolchain-free verifier for a {@link ProjectKind#CHROME_EXTENSION_MV3} surface.
 *
 * <p>A vanilla-JS MV3 extension has nothing to compile, so the old detector skipped it fail-open and
 * a structurally-broken extension shipped as PASS. This verifier turns that skip into a real build
 * gate: it reuses the same manifest reference-consistency rule as the code-generation validator
 * ({@link ManifestReferenceValidator}) — every code file the manifest references (service worker,
 * content scripts, popup/options HTML, declared resources) must exist. A failure is surfaced as a
 * {@code FAILED} {@link BuildReport} so the self-healing loop routes it back to code generation with
 * the unresolved references attached as diagnostics.
 *
 * <p>Binary asset references (icons/images) are intentionally NOT checked — the model cannot author a
 * PNG, so missing icons are backfilled by the packaging Assembler, not failed here.
 */
final class ExtensionStructureVerifier {

    private ExtensionStructureVerifier() {
    }

    static BuildReport verify(JsonNode sourceMap, String rootDir, ObjectMapper mapper) {
        List<String> problems = ManifestReferenceValidator.findMissingCodeReferences(sourceMap, mapper);
        String where = rootDir == null || rootDir.isEmpty() ? "<root>" : rootDir;

        if (problems.isEmpty()) {
            return BuildReport.success(BuildTool.NONE,
                    "Chrome extension (MV3) structure verified at [" + where + "] — manifest references resolve.");
        }

        List<BuildDiagnostic> diagnostics = problems.stream()
                .map(p -> BuildDiagnostic.error(rootDir + "manifest.json", 0, p))
                .toList();
        return BuildReport.failure(BuildTool.NONE, 1, diagnostics,
                "Chrome extension (MV3) structure invalid at [" + where + "]: "
                        + problems.size() + " unresolved manifest reference(s).",
                String.join("\n", problems), BuildPhase.COMPILE);
    }
}
