package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ExtensionWiringNormalizer")
class ExtensionWiringNormalizerTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new JacksonConfig().objectMapper();
    }

    // ── Popup HTML <script src> wiring ───────────────────────────────────────────

    @Test
    @DisplayName("Rewrites a script src that points a directory away to the module's real relative path")
    void rewritesMisresolvedScriptSrc() throws Exception {
        JsonNode src = mapper.readTree("""
                {
                  "frontend/popup.html": "<html><body><script src=\\"sidebar.js\\"></script></body></html>",
                  "frontend/src/sidebar.js": "export const x = 1;"
                }
                """);

        JsonNode out = ExtensionWiringNormalizer.normalize(src, mapper);
        String html = out.get("frontend/popup.html").asText();

        assertThat(html).contains("src=\"src/sidebar.js\"").doesNotContain("src=\"sidebar.js\"");
    }

    @Test
    @DisplayName("Injects a script tag for an unwired same-directory popup-companion module")
    void injectsOrphanCompanionModule() throws Exception {
        JsonNode src = mapper.readTree("""
                {
                  "popup.html": "<html><body><div id=\\"app\\"></div></body></html>",
                  "highlightFields.js": "function hl(){}"
                }
                """);

        JsonNode out = ExtensionWiringNormalizer.normalize(src, mapper);
        String html = out.get("popup.html").asText();

        assertThat(html).contains("<script src=\"highlightFields.js\">");
        assertThat(html.indexOf("<script")).isLessThan(html.indexOf("</body>"));
    }

    @Test
    @DisplayName("Leaves a correctly-wired popup untouched (no-op)")
    void correctlyWired_isNoOp() throws Exception {
        JsonNode src = mapper.readTree("""
                {
                  "popup.html": "<html><body><script src=\\"popup.js\\"></script></body></html>",
                  "popup.js": "console.log('ok');"
                }
                """);
        assertThat(ExtensionWiringNormalizer.normalize(src, mapper)).isSameAs(src);
    }

    @Test
    @DisplayName("Does not touch non-popup HTML")
    void nonPopupHtml_untouched() throws Exception {
        JsonNode src = mapper.readTree("""
                {
                  "index.html": "<html><body><script src=\\"app.js\\"></script></body></html>",
                  "src/app.js": "x();"
                }
                """);
        assertThat(ExtensionWiringNormalizer.normalize(src, mapper)).isSameAs(src);
    }

    @Test
    @DisplayName("Leaves an ambiguous-basename reference alone (no unsafe guess)")
    void ambiguousBasename_notRewritten() throws Exception {
        JsonNode src = mapper.readTree("""
                {
                  "popup.html": "<html><body><script src=\\"util.js\\"></script></body></html>",
                  "a/util.js": "1;",
                  "b/util.js": "2;"
                }
                """);
        assertThat(ExtensionWiringNormalizer.normalize(src, mapper)).isSameAs(src);
    }

    @Test
    @DisplayName("Does not rewrite or inject for external script URLs")
    void externalScript_untouched() throws Exception {
        JsonNode src = mapper.readTree("""
                {
                  "popup.html": "<html><body><script src=\\"https://cdn.example.com/lib.js\\"></script><script src=\\"popup.js\\"></script></body></html>",
                  "popup.js": "ok();"
                }
                """);
        assertThat(ExtensionWiringNormalizer.normalize(src, mapper)).isSameAs(src);
    }

    @Test
    @DisplayName("Does not wire a service worker / content script as a popup companion")
    void worker_notInjected() throws Exception {
        JsonNode src = mapper.readTree("""
                {
                  "popup.html": "<html><body><script src=\\"popup.js\\"></script></body></html>",
                  "popup.js": "ok();",
                  "background.js": "self.addEventListener('install', () => {});",
                  "content_script.js": "document.querySelectorAll('input');"
                }
                """);
        assertThat(ExtensionWiringNormalizer.normalize(src, mapper)).isSameAs(src);
    }

    // ── manifest.json code-reference wiring ──────────────────────────────────────

    @Test
    @DisplayName("Rewrites a manifest service_worker / content_scripts reference that points a directory away")
    void rewritesManifestReferences() throws Exception {
        JsonNode src = mapper.readTree("""
                {
                  "frontend/manifest.json": "{\\"manifest_version\\":3,\\"background\\":{\\"service_worker\\":\\"background.js\\"},\\"content_scripts\\":[{\\"matches\\":[\\"<all_urls>\\"],\\"js\\":[\\"content_script.js\\"]}],\\"action\\":{\\"default_popup\\":\\"popup.html\\"}}",
                  "frontend/src/background.js": "self.addEventListener('install', () => {});",
                  "frontend/src/content_script.js": "document.querySelectorAll('input');",
                  "frontend/popup.html": "<html><body></body></html>"
                }
                """);

        JsonNode out = ExtensionWiringNormalizer.normalize(src, mapper);
        JsonNode manifest = mapper.readTree(out.get("frontend/manifest.json").asText());

        assertThat(manifest.at("/background/service_worker").asText()).isEqualTo("src/background.js");
        assertThat(manifest.at("/content_scripts/0/js/0").asText()).isEqualTo("src/content_script.js");
        // popup.html is already at the manifest's load-root — left as-is.
        assertThat(manifest.at("/action/default_popup").asText()).isEqualTo("popup.html");
    }

    @Test
    @DisplayName("Prunes an unbacked manifest service_worker reference (drops the empty background block)")
    void prunesUnbackedServiceWorker() throws Exception {
        JsonNode src = mapper.readTree("""
                {
                  "frontend/manifest.json": "{\\"manifest_version\\":3,\\"background\\":{\\"service_worker\\":\\"background.js\\"},\\"action\\":{\\"default_popup\\":\\"popup.html\\"}}",
                  "frontend/popup.html": "<html><body></body></html>"
                }
                """);

        JsonNode out = ExtensionWiringNormalizer.normalize(src, mapper);
        JsonNode manifest = mapper.readTree(out.get("frontend/manifest.json").asText());

        assertThat(manifest.has("background")).isFalse();
        assertThat(manifest.at("/action/default_popup").asText()).isEqualTo("popup.html");
    }

    @Test
    @DisplayName("Prunes only the unbacked entry from a content_scripts js array")
    void prunesUnbackedContentScript() throws Exception {
        JsonNode src = mapper.readTree("""
                {
                  "manifest.json": "{\\"manifest_version\\":3,\\"content_scripts\\":[{\\"matches\\":[\\"<all_urls>\\"],\\"js\\":[\\"content_script.js\\",\\"ghost.js\\"]}]}",
                  "content_script.js": "document.querySelectorAll('input');"
                }
                """);

        JsonNode out = ExtensionWiringNormalizer.normalize(src, mapper);
        JsonNode js = mapper.readTree(out.get("manifest.json").asText()).at("/content_scripts/0/js");

        assertThat(js).hasSize(1);
        assertThat(js.get(0).asText()).isEqualTo("content_script.js");
    }

    @Test
    @DisplayName("Prunes an unused 'storage' permission no generated JS uses (least privilege)")
    void prunesUnusedStoragePermission() throws Exception {
        JsonNode src = mapper.readTree("""
                {
                  "manifest.json": "{\\"manifest_version\\":3,\\"permissions\\":[\\"storage\\",\\"activeTab\\"]}",
                  "popup.js": "document.getElementById('x');"
                }
                """);

        JsonNode out = ExtensionWiringNormalizer.normalize(src, mapper);
        JsonNode perms = mapper.readTree(out.get("manifest.json").asText()).at("/permissions");

        assertThat(perms).hasSize(1);
        assertThat(perms.get(0).asText()).isEqualTo("activeTab");
    }

    @Test
    @DisplayName("Keeps the 'storage' permission when some JS uses chrome.storage")
    void keepsStoragePermissionWhenUsed() throws Exception {
        JsonNode src = mapper.readTree("""
                {
                  "manifest.json": "{\\"manifest_version\\":3,\\"permissions\\":[\\"storage\\"]}",
                  "background.js": "chrome.storage.local.set({a:1});"
                }
                """);
        assertThat(ExtensionWiringNormalizer.normalize(src, mapper)).isSameAs(src);
    }

    @Test
    @DisplayName("Leaves a manifest with resolvable references untouched")
    void manifestAlreadyValid_isNoOp() throws Exception {
        JsonNode src = mapper.readTree("""
                {
                  "manifest.json": "{\\"manifest_version\\":3,\\"background\\":{\\"service_worker\\":\\"background.js\\"}}",
                  "background.js": "x();"
                }
                """);
        assertThat(ExtensionWiringNormalizer.normalize(src, mapper)).isSameAs(src);
    }

    @Test
    @DisplayName("Never rewrites manifest icon references (binary assets are the Assembler's job)")
    void manifestIcons_untouched() throws Exception {
        JsonNode src = mapper.readTree("""
                {
                  "manifest.json": "{\\"manifest_version\\":3,\\"icons\\":{\\"16\\":\\"images/icon16.png\\"},\\"background\\":{\\"service_worker\\":\\"sw.js\\"}}",
                  "sw.js": "x();"
                }
                """);
        // service_worker resolves; icons must not be considered a code reference -> no change at all.
        assertThat(ExtensionWiringNormalizer.normalize(src, mapper)).isSameAs(src);
    }
}
