package com.midas.d3.agent.implementation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SymbolTableExtractorTest {

    @Test
    void htmlFile_surfacesElementIds() {
        String html = "<!DOCTYPE html><html><body>"
                + "<button id=\"save-btn\">Save</button><div id=\"status\"></div>"
                + "</body></html>";

        String summary = SymbolTableExtractor.summarize(html, "frontend/popup.html");

        assertThat(summary).contains("element ids:").contains("#save-btn").contains("#status");
    }

    @Test
    void jsFile_surfacesSentAndHandledMessageActions() {
        String js = """
                chrome.runtime.sendMessage({ action: 'saveSemanticData', payload: {} });
                chrome.runtime.onMessage.addListener((msg) => {
                  if (msg.action === 'ackSaved') { console.log('ok'); }
                });
                """;

        String summary = SymbolTableExtractor.summarize(js, "frontend/content_script.js");

        assertThat(summary).contains("sends messages:").contains("'saveSemanticData'");
        assertThat(summary).contains("handles messages:").contains("'ackSaved'");
    }

    @Test
    void javaFile_hasNoExtensionContractHints() {
        String java = "public class Service { public void run() {} }";

        String summary = SymbolTableExtractor.summarize(java, "src/main/java/Service.java");

        assertThat(summary).doesNotContain("element ids:").doesNotContain("sends messages:");
        // The signature table still works.
        assertThat(summary).contains("public class Service");
    }

    @Test
    void noArgSummarize_remainsSignatureOnly() {
        String html = "<div id=\"x\"></div>";
        // The path-less overload must not inject contract hints (back-compat).
        assertThat(SymbolTableExtractor.summarize(html)).doesNotContain("element ids:");
    }
}
