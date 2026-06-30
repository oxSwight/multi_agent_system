package com.midas.d3.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestReferenceValidatorTest {

    private ObjectMapper objectMapper;
    private List<String> violations;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
        violations = new ArrayList<>();
    }

    @Test
    void missingServiceWorkerAndContentScript_areReported() {
        ObjectNode sourceFiles = objectMapper.createObjectNode();
        sourceFiles.put("manifest.json", """
                {
                  "manifest_version": 3,
                  "name": "Test",
                  "background": {"service_worker": "background.js"},
                  "content_scripts": [{"matches": ["<all_urls>"], "js": ["content_script.js"]}],
                  "action": {"default_popup": "popup.html"}
                }
                """);
        // Only popup.html exists — background.js and content_script.js are dangling references.
        sourceFiles.put("popup.html", "<!DOCTYPE html><html><body></body></html>");

        ManifestReferenceValidator.validateSourceFiles(sourceFiles, objectMapper, violations);

        assertThat(violations).anyMatch(v -> v.contains("background.service_worker") && v.contains("background.js"));
        assertThat(violations).anyMatch(v -> v.contains("content_scripts.js") && v.contains("content_script.js"));
        assertThat(violations).noneMatch(v -> v.contains("popup.html"));
    }

    @Test
    void referencesResolvedRelativeToManifestDirectory_passWhenPresent() {
        ObjectNode sourceFiles = objectMapper.createObjectNode();
        sourceFiles.put("frontend/manifest.json", """
                {
                  "manifest_version": 3,
                  "background": {"service_worker": "background.js"},
                  "content_scripts": [{"matches": ["<all_urls>"], "js": ["content_script.js"]}],
                  "action": {"default_popup": "popup.html"}
                }
                """);
        sourceFiles.put("frontend/background.js", "self.addEventListener('message', () => {});");
        sourceFiles.put("frontend/content_script.js", "console.log('cs');");
        sourceFiles.put("frontend/popup.html", "<!DOCTYPE html><html></html>");

        ManifestReferenceValidator.validateSourceFiles(sourceFiles, objectMapper, violations);

        assertThat(violations).isEmpty();
    }

    @Test
    void missingIconAsset_isNotReported_leftForAssembler() {
        ObjectNode sourceFiles = objectMapper.createObjectNode();
        sourceFiles.put("manifest.json", """
                {
                  "manifest_version": 3,
                  "background": {"service_worker": "background.js"},
                  "icons": {"16": "images/icon16.png", "128": "images/icon128.png"}
                }
                """);
        sourceFiles.put("background.js", "// worker");

        ManifestReferenceValidator.validateSourceFiles(sourceFiles, objectMapper, violations);

        // Icons are binary — never a code-generation violation; the Assembler backfills them.
        assertThat(violations).noneMatch(v -> v.contains("icon"));
        assertThat(violations).isEmpty();
    }

    @Test
    void invalidManifestJson_isReported() {
        ObjectNode sourceFiles = objectMapper.createObjectNode();
        sourceFiles.put("manifest.json", "{ not valid json ");

        ManifestReferenceValidator.validateSourceFiles(sourceFiles, objectMapper, violations);

        assertThat(violations).anyMatch(v -> v.contains("not valid JSON"));
    }

    @Test
    void externalUrlReference_isSkipped() {
        ObjectNode sourceFiles = objectMapper.createObjectNode();
        sourceFiles.put("manifest.json", """
                {
                  "manifest_version": 3,
                  "content_scripts": [{"matches": ["<all_urls>"], "css": ["https://cdn.example.com/x.css"]}]
                }
                """);

        ManifestReferenceValidator.validateSourceFiles(sourceFiles, objectMapper, violations);

        assertThat(violations).isEmpty();
    }
}
