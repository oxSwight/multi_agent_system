package com.midas.d3.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FrontendIntegrationValidatorTest {

    private ObjectMapper objectMapper;
    private List<String> violations;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
        violations = new ArrayList<>();
    }

    @Test
    void validateSourceFiles_orphanJsAndMissingScript_detectsIntegrationFailures() throws Exception {
        ObjectNode sourceFiles = objectMapper.createObjectNode();
        sourceFiles.put("frontend/src/popup.html", """
                <!DOCTYPE html><html><body>
                <button id="upload-btn">Upload</button>
                <script src="popup.js"></script>
                </body></html>
                """);
        sourceFiles.put("frontend/src/file_uploader.js", "document.getElementById('upload-btn');");
        sourceFiles.put("frontend/src/profile_manager.js", "document.getElementById('upload-btn');");

        FrontendIntegrationValidator.validateSourceFiles(sourceFiles, null, violations);

        assertThat(violations).anyMatch(v -> v.contains("missing script") || v.contains("popup.js"));
        assertThat(violations).anyMatch(v -> v.contains("Orphan JavaScript"));
    }

    @Test
    void validateSourceFiles_wiredPopup_passesIntegrationChecks() throws Exception {
        ObjectNode sourceFiles = objectMapper.createObjectNode();
        sourceFiles.put("frontend/src/popup.html", """
                <!DOCTYPE html><html><body>
                <div id="status"></div>
                <script src="file_uploader.js"></script>
                <script src="profile_manager.js"></script>
                </body></html>
                """);
        sourceFiles.put("frontend/src/file_uploader.js", """
                document.getElementById('status').textContent = 'ok';
                fetch('http://localhost:8080/api/files');
                """);
        sourceFiles.put("frontend/src/profile_manager.js", "chrome.storage.local.get('profiles');");
        sourceFiles.put("frontend/src/popup.css", "* { box-sizing: border-box; } body { width: 360px; }");
        sourceFiles.put("frontend/manifest.json", """
                {"permissions":["storage"],"host_permissions":["http://localhost:8080/*"]}
                """);

        ObjectNode architecture = objectMapper.createObjectNode();
        architecture.putArray("api_contracts").addObject()
                .put("method", "POST")
                .put("path", "/api/files");

        FrontendIntegrationValidator.validateSourceFiles(sourceFiles, architecture, violations);

        assertThat(violations).isEmpty();
    }

    @Test
    void validateSourceFiles_alertAndMissingBoxSizing_areRejected() throws Exception {
        ObjectNode sourceFiles = objectMapper.createObjectNode();
        sourceFiles.put("frontend/src/popup.html", """
                <html><body><script src="popup.js"></script></body></html>
                """);
        sourceFiles.put("frontend/src/popup.js", "alert('done');");
        sourceFiles.put("frontend/src/popup.css", "body { margin: 0; }");

        FrontendIntegrationValidator.validateSourceFiles(sourceFiles, null, violations);

        assertThat(violations).anyMatch(v -> v.contains("alert()"));
        assertThat(violations).anyMatch(v -> v.contains("box-sizing"));
    }

    @Test
    void validateTestAgainstSource_hallucinatedId_isRejected() throws Exception {
        ObjectNode generatedSource = objectMapper.createObjectNode();
        generatedSource.put("frontend/src/popup.html", "<button id=\"add-profile-btn\"></button>");

        String testContent = """
                const btn = document.getElementById('addProfileButton');
                expect(btn).toBeTruthy();
                """;

        FrontendIntegrationValidator.validateTestAgainstSource(
                "frontend/__tests__/popup.test.js", testContent, generatedSource, null, violations);

        assertThat(violations).anyMatch(v -> v.contains("addProfileButton"));
    }

    @Test
    void validateTestAgainstSource_wrongApiPath_isRejected() throws Exception {
        ObjectNode generatedSource = objectMapper.createObjectNode();
        generatedSource.put("frontend/src/file_uploader.js", "export function upload() {}");

        ObjectNode architecture = objectMapper.createObjectNode();
        architecture.putArray("api_contracts").addObject()
                .put("method", "POST")
                .put("path", "/api/files");

        String testContent = """
                fetch('http://localhost:3000/upload');
                """;

        FrontendIntegrationValidator.validateTestAgainstSource(
                "frontend/__tests__/file_uploader.test.js", testContent, generatedSource, architecture, violations);

        assertThat(violations).anyMatch(v -> v.contains("/upload") || v.contains("hallucinated"));
    }
}
