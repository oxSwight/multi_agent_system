package com.midas.d3.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.midas.d3.artifact.ArtifactProperties;
import com.midas.d3.config.JacksonConfig;
import com.midas.d3.context.MidasContext;
import com.midas.d3.telegram.ArtifactPackagingService;
import com.midas.d3.validation.FeatureManifestValidator;
import com.midas.d3.validation.ImplementationEngineerValidator;
import com.midas.d3.validation.ValidationHookException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance test for the deterministic Chrome-extension barriers (P0). It reproduces the exact
 * MV3 extension that previously passed the pipeline as PASS yet would not load in
 * {@code chrome://extensions}, and asserts two things end-to-end:
 *
 * <ol>
 *   <li><b>The gate rejects a structurally-broken artifact</b> — the manifest buried under an extra
 *       wrapper directory and a dangling content-script reference are caught deterministically,
 *       not waved through on self-report.</li>
 *   <li><b>The Assembler produces a loadable artifact</b> — the corrected source packages with the
 *       manifest at its real load-root, valid placeholder icons backfilled for every icon
 *       reference, and a README carrying install/usage.</li>
 * </ol>
 */
@DisplayName("Chrome extension P0 acceptance")
class ChromeExtensionAcceptanceTest {

    private ObjectMapper objectMapper;
    private ImplementationEngineerValidator validator;
    private ArtifactPackagingService packagingService;

    /** Durable artifact directory the packaging service writes result ZIPs into. */
    @TempDir
    Path artifactDir;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
        validator = new ImplementationEngineerValidator(objectMapper, new FeatureManifestValidator());
        ArtifactProperties artifactProperties = new ArtifactProperties();
        artifactProperties.setDir(artifactDir.toString());
        packagingService = new ArtifactPackagingService(objectMapper, artifactProperties);
    }

    @Test
    @DisplayName("Structurally-broken extension is rejected by the deterministic gates")
    void brokenExtension_isRejectedByDeterministicGates() throws Exception {
        JsonNode architecture = objectMapper.readTree("""
                {
                  "file_layout": [
                    "frontend/manifest.json",
                    "frontend/background.js",
                    "frontend/content_script.js",
                    "frontend/popup.html",
                    "frontend/popup.js"
                  ],
                  "api_contracts": []
                }
                """);
        JsonNode technicalSpec = objectMapper.readTree("""
                {"business_goal": "Save semantic page data", "core_features": ["Save semantic data"]}
                """);

        ObjectNode sourceFiles = objectMapper.createObjectNode();
        // Defect 1: the whole extension is buried under an extra src/ wrapper, so the manifest is
        // NOT at the frontend/ load-root the architecture declared.
        sourceFiles.put("src/frontend/manifest.json", """
                {
                  "manifest_version": 3,
                  "name": "Semantic Saver",
                  "background": {"service_worker": "background.js"},
                  "content_scripts": [{"matches": ["<all_urls>"], "js": ["content_script.js"]}],
                  "action": {"default_popup": "popup.html"},
                  "icons": {"16": "images/icon16.png"}
                }
                """);
        sourceFiles.put("src/frontend/background.js", "self.addEventListener('install', () => {});");
        sourceFiles.put("src/frontend/popup.html",
                "<!DOCTYPE html><html><body><div id=\"status\"></div><script src=\"popup.js\"></script></body></html>");
        sourceFiles.put("src/frontend/popup.js", "document.getElementById('status');");
        // Defect 2: content_script.js is referenced by the manifest but was never generated.

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.set("source_files", sourceFiles);
        envelope.putArray("feature_manifest")
                .addObject()
                .put("feature_id", "save-semantic-data")
                .put("feature_name", "Save semantic data")
                .set("files", objectMapper.createArrayNode().add("src/frontend/background.js"));
        ((ObjectNode) envelope.get("feature_manifest").get(0))
                .set("entry_points", objectMapper.createArrayNode().add("background"));

        String json = objectMapper.writeValueAsString(envelope);

        assertThatThrownBy(() -> validator.validateWithTechnicalSpec(json, technicalSpec, architecture))
                .isInstanceOf(ValidationHookException.class)
                .satisfies(thrown -> {
                    ValidationHookException e = (ValidationHookException) thrown;
                    // Path-conformance caught the wrapper drift…
                    assertThat(e.getViolations())
                            .anyMatch(v -> v.contains("wrapper") && v.contains("frontend/manifest.json"));
                    // …and the manifest reference check caught the dangling content script.
                    assertThat(e.getViolations())
                            .anyMatch(v -> v.contains("content_script.js") && v.contains("missing"));
                });
    }

    @Test
    @DisplayName("Corrected extension packages into a loadable artifact (manifest at root, icons, README)")
    void correctedExtension_packagesIntoLoadableArtifact() throws IOException {
        ObjectNode code = objectMapper.createObjectNode();
        code.put("frontend/manifest.json", """
                {
                  "manifest_version": 3,
                  "name": "Semantic Saver",
                  "background": {"service_worker": "background.js"},
                  "content_scripts": [{"matches": ["<all_urls>"], "js": ["content_script.js"]}],
                  "action": {"default_popup": "popup.html"},
                  "icons": {"16": "images/icon16.png", "48": "images/icon48.png", "128": "images/icon128.png"}
                }
                """);
        code.put("frontend/background.js",
                "chrome.runtime.onMessage.addListener((m) => { if (m.action === 'saveSemanticData') {} });");
        code.put("frontend/content_script.js",
                "chrome.runtime.sendMessage({ action: 'saveSemanticData', payload: {} });");
        code.put("frontend/popup.html",
                "<!DOCTYPE html><html><body><div id=\"status\"></div><script src=\"popup.js\"></script></body></html>");
        code.put("frontend/popup.js", "document.getElementById('status').textContent = 'ready';");
        code.put("frontend/popup.css", "* { box-sizing: border-box; } body { width: 360px; }");

        MidasContext ctx = MidasContext.start("Save the semantic content of the current page", "run-accept-ext")
                .withGeneratedSourceCode(code);

        File zip = packagingService.packageResults(ctx);
        try {
            Set<String> entries = listZipEntries(zip);

            // Manifest at its real load-root; no wrapper, no duplicate tree.
            assertThat(entries)
                    .contains("frontend/manifest.json")
                    .contains("frontend/background.js")
                    .contains("frontend/content_script.js")
                    .doesNotContain("src/frontend/manifest.json");

            // Every icon reference is backfilled with a valid PNG so the extension loads.
            for (String icon : new String[]{
                    "frontend/images/icon16.png", "frontend/images/icon48.png", "frontend/images/icon128.png"}) {
                assertThat(entries).contains(icon);
                assertThat(isValidPng(readBytes(zip, icon)))
                        .as("placeholder icon [%s] must be a valid PNG", icon).isTrue();
            }

            // README tells the user how to load it, pointing at the computed load-root.
            String readme = readString(zip, "README.md");
            assertThat(readme)
                    .contains("Load unpacked")
                    .contains("frontend")
                    .contains("Semantic Saver");
        } finally {
            zip.delete();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Set<String> listZipEntries(File zip) throws IOException {
        try (ZipFile zf = new ZipFile(zip)) {
            return zf.stream().map(ZipEntry::getName).collect(Collectors.toSet());
        }
    }

    private byte[] readBytes(File zip, String entryName) throws IOException {
        try (ZipFile zf = new ZipFile(zip)) {
            ZipEntry entry = zf.getEntry(entryName);
            assertThat(entry).as("Entry [%s] not found in ZIP", entryName).isNotNull();
            return zf.getInputStream(entry).readAllBytes();
        }
    }

    private String readString(File zip, String entryName) throws IOException {
        return new String(readBytes(zip, entryName), java.nio.charset.StandardCharsets.UTF_8);
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
}
