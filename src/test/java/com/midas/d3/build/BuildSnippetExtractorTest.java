package com.midas.d3.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BuildSnippetExtractor} — the deterministic, best-effort enrichment that
 * slices the offending source line out of the compiled source map and attaches it to each
 * file-attributed {@link BuildDiagnostic}.
 */
@DisplayName("BuildSnippetExtractor")
class BuildSnippetExtractorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode sourceMap(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BuildReport failureWith(BuildDiagnostic... diagnostics) {
        return BuildReport.failure(BuildTool.MAVEN, 1, List.of(diagnostics),
                "compile failed", "[ERROR] compile failed");
    }

    @Nested
    @DisplayName("resolves and slices the offending line")
    class Resolves {

        @Test
        @DisplayName("attaches a context window with the offending line marked, via path-suffix match")
        void attachesMarkedSnippet() {
            // Compiler reports the bare file name; the map is keyed by the project-relative path.
            JsonNode map = sourceMap("""
                    {"src/main/java/App.java": "line1\\nline2\\nBROKEN\\nline4\\nline5"}
                    """);
            BuildReport enriched = BuildSnippetExtractor.enrich(
                    failureWith(BuildDiagnostic.error("App.java", 3, "cannot find symbol")), map);

            String snippet = enriched.diagnostics().get(0).codeSnippet();
            assertThat(snippet).contains("> 3 | BROKEN");
            assertThat(snippet).contains("  1 | line1");   // radius = 2 → starts at line 1
            assertThat(snippet).contains("  5 | line5");   // radius = 2 → ends at line 5
            assertThat(snippet).doesNotContain("line6");
        }

        @Test
        @DisplayName("clamps the context window at the start of the file")
        void clampsAtStart() {
            JsonNode map = sourceMap("""
                    {"A.java": "first\\nsecond\\nthird"}
                    """);
            BuildReport enriched = BuildSnippetExtractor.enrich(
                    failureWith(BuildDiagnostic.error("A.java", 1, "boom")), map);

            String snippet = enriched.diagnostics().get(0).codeSnippet();
            assertThat(snippet).startsWith("> 1 | first");
            assertThat(snippet).contains("  3 | third");
        }

        @Test
        @DisplayName("matches an absolute sandbox path against a relative map key")
        void matchesAbsoluteSandboxPath() {
            JsonNode map = sourceMap("""
                    {"src/main/java/com/x/Svc.java": "a\\nb\\nc\\nd"}
                    """);
            BuildReport enriched = BuildSnippetExtractor.enrich(
                    failureWith(BuildDiagnostic.error(
                            "/tmp/midas-run-1/src/main/java/com/x/Svc.java", 2, "err")), map);

            assertThat(enriched.diagnostics().get(0).codeSnippet()).contains("> 2 | b");
        }

        @Test
        @DisplayName("prefers the longest path-suffix match so a short name can't shadow a specific one")
        void prefersLongestMatch() {
            JsonNode map = sourceMap("""
                    {"App.java": "WRONG", "module/web/App.java": "x\\nRIGHT\\nz"}
                    """);
            // Absolute sandbox path → no exact key, so the suffix loop must pick the longer match.
            BuildReport enriched = BuildSnippetExtractor.enrich(
                    failureWith(BuildDiagnostic.error("/sandbox/module/web/App.java", 2, "err")), map);

            assertThat(enriched.diagnostics().get(0).codeSnippet()).contains("> 2 | RIGHT");
        }

        @Test
        @DisplayName("caps an enormous single line at MAX_SNIPPET_CHARS")
        void capsHugeLine() {
            String huge = "x".repeat(5_000);
            JsonNode map = sourceMap(mapper.createObjectNode().put("Big.java", huge).toString());
            BuildReport enriched = BuildSnippetExtractor.enrich(
                    failureWith(BuildDiagnostic.error("Big.java", 1, "err")), map);

            assertThat(enriched.diagnostics().get(0).codeSnippet().length())
                    .isLessThanOrEqualTo(BuildSnippetExtractor.MAX_SNIPPET_CHARS);
        }
    }

    @Nested
    @DisplayName("leaves things untouched when it cannot help")
    class NoOp {

        @Test
        @DisplayName("a passing report is returned as the same instance")
        void passingReportUnchanged() {
            BuildReport ok = BuildReport.success(BuildTool.MAVEN, "ok");
            assertThat(BuildSnippetExtractor.enrich(ok, sourceMap("{\"A.java\":\"a\"}"))).isSameAs(ok);
        }

        @Test
        @DisplayName("an unresolved diagnostic leaves the report instance unchanged (no churn)")
        void unresolvedReportUnchanged() {
            BuildReport report = failureWith(BuildDiagnostic.error("Missing.java", 2, "err"));
            assertThat(BuildSnippetExtractor.enrich(report, sourceMap("{\"Other.java\":\"a\\nb\"}")))
                    .isSameAs(report);
        }

        @Test
        @DisplayName("line out of range yields no snippet")
        void lineOutOfRange() {
            BuildReport report = failureWith(BuildDiagnostic.error("A.java", 99, "err"));
            BuildReport result = BuildSnippetExtractor.enrich(report, sourceMap("{\"A.java\":\"only-one-line\"}"));
            assertThat(result.diagnostics().get(0).hasSnippet()).isFalse();
        }

        @Test
        @DisplayName("unattributed (unknown file / line 0) diagnostics are skipped")
        void unattributedSkipped() {
            BuildReport report = failureWith(BuildDiagnostic.error("unknown", 0, "generic error"));
            BuildReport result = BuildSnippetExtractor.enrich(report, sourceMap("{\"A.java\":\"a\\nb\"}"));
            assertThat(result.diagnostics().get(0).hasSnippet()).isFalse();
        }

        @Test
        @DisplayName("null report and null source map are tolerated")
        void nullsTolerated() {
            assertThat(BuildSnippetExtractor.enrich(null, sourceMap("{}"))).isNull();
            BuildReport report = failureWith(BuildDiagnostic.error("A.java", 1, "e"));
            assertThat(BuildSnippetExtractor.enrich(report, null)).isSameAs(report);
        }
    }

    @Test
    @DisplayName("enriched snippet survives into the canonical JSON artifact as code_snippet")
    void snippetReachesJsonArtifact() {
        JsonNode map = sourceMap("""
                {"src/main/java/App.java": "class App {\\n  int x = y;\\n}"}
                """);
        BuildReport enriched = BuildSnippetExtractor.enrich(
                failureWith(BuildDiagnostic.error("App.java", 2, "cannot find symbol y")), map);

        JsonNode json = enriched.toJson(mapper);
        JsonNode diag = json.get("diagnostics").get(0);
        assertThat(diag.has("code_snippet")).isTrue();
        assertThat(diag.get("code_snippet").asText()).contains("> 2 |").contains("int x = y;");
    }
}
