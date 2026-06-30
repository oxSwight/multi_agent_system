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
    void validateSourceFiles_jsQueriesIdMissingFromHtml_isRejected() throws Exception {
        ObjectNode sourceFiles = objectMapper.createObjectNode();
        sourceFiles.put("frontend/src/popup.html",
                "<button id=\"add-profile-btn\"></button><script src=\"popup.js\"></script>");
        sourceFiles.put("frontend/src/popup.js", "document.getElementById('addProfileButton').click();");
        sourceFiles.put("frontend/src/popup.css", "* { box-sizing: border-box; }");

        FrontendIntegrationValidator.validateSourceFiles(sourceFiles, null, violations);

        assertThat(violations).anyMatch(v -> v.contains("addProfileButton") && v.contains("element id"));
    }

    @Test
    void validateSourceFiles_dynamicallyCreatedId_isTolerated() throws Exception {
        ObjectNode sourceFiles = objectMapper.createObjectNode();
        sourceFiles.put("frontend/src/popup.html",
                "<div id=\"list\"></div><script src=\"popup.js\"></script>");
        sourceFiles.put("frontend/src/popup.js", """
                const row = document.createElement('div');
                row.id = 'dynamic-row';
                document.getElementById('list').appendChild(row);
                document.getElementById('dynamic-row').textContent = 'x';
                """);
        sourceFiles.put("frontend/src/popup.css", "* { box-sizing: border-box; }");

        FrontendIntegrationValidator.validateSourceFiles(sourceFiles, null, violations);

        // 'dynamic-row' is created in JS, so it must not be flagged as a missing id.
        assertThat(violations).noneMatch(v -> v.contains("dynamic-row"));
    }

    @Test
    void validateSourceFiles_unhandledMessageAction_isRejected() throws Exception {
        ObjectNode sourceFiles = objectMapper.createObjectNode();
        sourceFiles.put("frontend/src/content_script.js",
                "chrome.runtime.sendMessage({ action: 'saveSemanticData', payload: {} });");
        sourceFiles.put("frontend/src/background.js", """
                chrome.runtime.onMessage.addListener((msg) => {
                  if (msg.action === 'somethingElse') { /* ... */ }
                });
                """);

        FrontendIntegrationValidator.validateSourceFiles(sourceFiles, null, violations);

        assertThat(violations).anyMatch(v -> v.contains("saveSemanticData") && v.contains("onMessage"));
    }

    @Test
    void validateSourceFiles_handledMessageAction_passes() throws Exception {
        ObjectNode sourceFiles = objectMapper.createObjectNode();
        sourceFiles.put("frontend/src/content_script.js",
                "chrome.runtime.sendMessage({ action: 'saveSemanticData', payload: {} });");
        sourceFiles.put("frontend/src/background.js", """
                chrome.runtime.onMessage.addListener((msg) => {
                  if (msg.action === 'saveSemanticData') { /* handle */ }
                });
                """);

        FrontendIntegrationValidator.validateSourceFiles(sourceFiles, null, violations);

        assertThat(violations).noneMatch(v -> v.contains("sends runtime message action"));
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
    void validateTestAgainstSource_contentScriptMockDomIds_areAccepted() throws Exception {
        // A content-script test builds its own synthetic page DOM with realistic field ids that do not
        // (and should not) exist in the extension's own HTML — these must not be flagged.
        ObjectNode generatedSource = objectMapper.createObjectNode();
        generatedSource.put("frontend/src/content_script.js",
                "export function scan(){ return document.querySelectorAll('input'); }");
        generatedSource.put("frontend/src/popup.html", "<button id=\"fill-btn\"></button>");

        String testContent = """
                import { scan } from '../src/content_script.js';
                document.body.innerHTML = '<form><input id="firstName"><input id="email"></form>';
                const a = document.getElementById('firstName');
                const b = document.getElementById('email');
                expect(scan()).toBeTruthy();
                """;

        FrontendIntegrationValidator.validateTestAgainstSource(
                "frontend/__tests__/content_script.test.js", testContent, generatedSource, null, violations);

        assertThat(violations).noneMatch(v -> v.contains("firstName") || v.contains("email"));
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

    @Test
    void validateSourceFiles_parameterizedContractPath_isAccepted() throws Exception {
        // The exact shape that wedged the first live run: a correct ${...} template fill of a
        // brace-placeholder contract path must NOT be flagged as an invented endpoint.
        ObjectNode sourceFiles = objectMapper.createObjectNode();
        sourceFiles.put("frontend/src/popup.html", """
                <!DOCTYPE html><html><body>
                <div id="status"></div>
                <script src="popup.js"></script>
                </body></html>
                """);
        sourceFiles.put("frontend/src/popup.js", """
                const API_BASE_URL = 'http://localhost:8080';
                const selectedProfileId = '1';
                fetch(`${API_BASE_URL}/api/resumes/${selectedProfileId}`);
                fetch(`${API_BASE_URL}/api/match`);
                """);
        sourceFiles.put("frontend/src/popup.css", "* { box-sizing: border-box; } body { width: 360px; }");

        ObjectNode architecture = objectMapper.createObjectNode();
        var contracts = architecture.putArray("api_contracts");
        contracts.addObject().put("method", "GET").put("path", "/api/resumes/{profileId}");
        contracts.addObject().put("method", "POST").put("path", "/api/match");

        FrontendIntegrationValidator.validateSourceFiles(sourceFiles, architecture, violations);

        assertThat(violations).noneMatch(v -> v.contains("do not invent endpoints"));
    }

    @Test
    void validateSourceFiles_invitedEndpoint_stillRejectedDespiteParamContract() throws Exception {
        // The fix must not become a rubber stamp: a path with different literal segments is still
        // an invented endpoint even though the contract is parameterized.
        ObjectNode sourceFiles = objectMapper.createObjectNode();
        sourceFiles.put("frontend/src/popup.html", """
                <!DOCTYPE html><html><body>
                <div id="status"></div>
                <script src="popup.js"></script>
                </body></html>
                """);
        sourceFiles.put("frontend/src/popup.js", """
                const API_BASE_URL = 'http://localhost:8080';
                fetch(`${API_BASE_URL}/api/users/${userId}`);
                """);
        sourceFiles.put("frontend/src/popup.css", "* { box-sizing: border-box; }");

        ObjectNode architecture = objectMapper.createObjectNode();
        architecture.putArray("api_contracts").addObject()
                .put("method", "GET").put("path", "/api/resumes/{profileId}");

        FrontendIntegrationValidator.validateSourceFiles(sourceFiles, architecture, violations);

        assertThat(violations).anyMatch(v -> v.contains("/api/users/") && v.contains("do not invent endpoints"));
    }

    @Test
    void validateTestAgainstSource_parameterizedContractPath_isAccepted() throws Exception {
        ObjectNode generatedSource = objectMapper.createObjectNode();
        generatedSource.put("frontend/src/profile_manager.js", "export function load() {}");

        ObjectNode architecture = objectMapper.createObjectNode();
        architecture.putArray("api_contracts").addObject()
                .put("method", "GET").put("path", "/api/resumes/{profileId}");

        String testContent = """
                const profile_manager = require('profile_manager');
                fetch(`${API_BASE_URL}/api/resumes/${id}`);
                """;

        FrontendIntegrationValidator.validateTestAgainstSource(
                "frontend/__tests__/profile_manager.test.js", testContent, generatedSource, architecture, violations);

        assertThat(violations).noneMatch(v -> v.contains("hallucinated endpoint"));
    }
}
