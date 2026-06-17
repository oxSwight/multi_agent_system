package com.midas.d3.sanitizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

@DisplayName("JsonSanitizer Tests")
class JsonSanitizerTest {

    private static final String VALID_JSON = """
            {"business_goal": "Build a todo app", "core_features": ["Create"]}""";

    // ── Null / blank input ────────────────────────────────────────────────────

    @Nested
    class NullAndBlankInputs {

        @Test
        void sanitize_null_returnsNull() {
            assertThat(JsonSanitizer.sanitize(null)).isNull();
        }

        @Test
        void sanitize_blank_returnsBlank() {
            assertThat(JsonSanitizer.sanitize("   ")).isEqualTo("   ");
        }
    }

    // ── Strategy 1: Markdown code fence extraction ────────────────────────────

    @Nested
    class MarkdownFenceExtraction {

        @Test
        @DisplayName("Extracts content from ```json ... ``` fence")
        void sanitize_jsonFence_extractsContent() {
            String input = "```json\n" + VALID_JSON + "\n```";
            assertThat(JsonSanitizer.sanitize(input)).isEqualTo(VALID_JSON);
        }

        @Test
        @DisplayName("Extracts content from ``` ... ``` fence (no lang tag)")
        void sanitize_genericFence_extractsContent() {
            String input = "```\n" + VALID_JSON + "\n```";
            assertThat(JsonSanitizer.sanitize(input)).isEqualTo(VALID_JSON);
        }

        @Test
        @DisplayName("Handles JSON fence with preamble text before the fence")
        void sanitize_preambleBeforeFence_extractsJson() {
            String input = "Here is the output:\n```json\n" + VALID_JSON + "\n```";
            assertThat(JsonSanitizer.sanitize(input)).isEqualTo(VALID_JSON);
        }

        @Test
        @DisplayName("Handles LLM explanation after closing fence")
        void sanitize_explanationAfterFence_extractsJson() {
            String input = "```json\n" + VALID_JSON + "\n```\n\nNote: this is required.";
            assertThat(JsonSanitizer.sanitize(input)).isEqualTo(VALID_JSON);
        }

        @Test
        @DisplayName("Handles multi-line JSON inside fence")
        void sanitize_multiLineJsonInFence_extractsCorrectly() {
            String multiLine = "{\n  \"a\": 1,\n  \"b\": [1, 2]\n}";
            String input = "```json\n" + multiLine + "\n```";
            assertThat(JsonSanitizer.sanitize(input)).isEqualTo(multiLine);
        }

        @ParameterizedTest
        @ValueSource(strings = {"json", "JSON", "Json"})
        @DisplayName("Handles case-insensitive language tag")
        void sanitize_caseInsensitiveLangTag_extracts(String langTag) {
            String input = "```" + langTag + "\n" + VALID_JSON + "\n```";
            assertThat(JsonSanitizer.sanitize(input)).isEqualTo(VALID_JSON);
        }

        @Test
        @DisplayName("Handles CRLF line endings in fence")
        void sanitize_crlfLineEndings_extracts() {
            String input = "```json\r\n" + VALID_JSON + "\r\n```";
            assertThat(JsonSanitizer.sanitize(input)).isEqualTo(VALID_JSON);
        }
    }

    // ── Strategy 2: Bare JSON (already starts with {) ──────────────────────

    @Nested
    class BareJsonInput {

        @Test
        @DisplayName("Returns trimmed JSON when input starts with {")
        void sanitize_bareJson_returnsTrimmed() {
            assertThat(JsonSanitizer.sanitize("  " + VALID_JSON + "  ")).isEqualTo(VALID_JSON);
        }

        @Test
        @DisplayName("Handles JSON with trailing newline")
        void sanitize_jsonWithTrailingNewline_trimmed() {
            assertThat(JsonSanitizer.sanitize(VALID_JSON + "\n")).isEqualTo(VALID_JSON);
        }
    }

    // ── Strategy 3: Brace scan extraction ────────────────────────────────────

    @Nested
    class BraceScanExtraction {

        @Test
        @DisplayName("Extracts JSON object embedded in prose text")
        void sanitize_jsonInProseText_extractsJson() {
            String input = "The result is: " + VALID_JSON + " That's all.";
            assertThat(JsonSanitizer.sanitize(input)).isEqualTo(VALID_JSON);
        }

        @Test
        @DisplayName("Handles braces inside string values correctly")
        void sanitize_bracesInsideStrings_correctlyExtracted() {
            String json = """
                    {"key": "value with {braces} inside", "n": 1}""";
            String input = "Output: " + json;
            assertThat(JsonSanitizer.sanitize(input)).isEqualTo(json);
        }

        @Test
        @DisplayName("Handles escaped quotes inside string values")
        void sanitize_escapedQuotesInValues_correctlyExtracted() {
            String json = """
                    {"message": "He said \\"hello\\"", "code": 200}""";
            String input = "Response: " + json;
            assertThat(JsonSanitizer.sanitize(input)).isEqualTo(json);
        }

        @Test
        @DisplayName("Handles nested JSON objects")
        void sanitize_nestedObjects_fullyExtracted() {
            String json = """
                    {"outer": {"inner": {"deep": "value"}}, "num": 42}""";
            String input = "Here: " + json + " end.";
            assertThat(JsonSanitizer.sanitize(input)).isEqualTo(json);
        }
    }

    // ── extractJsonObject unit tests ──────────────────────────────────────────

    @Nested
    class ExtractJsonObjectTests {

        @Test
        void simpleObject() {
            assertThat(JsonSanitizer.extractJsonObject("{\"k\": 1}", 0)).isEqualTo("{\"k\": 1}");
        }

        @Test
        void objectWithPreamble() {
            String s = "preamble {\"k\": 1} suffix";
            assertThat(JsonSanitizer.extractJsonObject(s, 9)).isEqualTo("{\"k\": 1}");
        }

        @Test
        void nestedObject() {
            String s = "{\"a\": {\"b\": 2}}";
            assertThat(JsonSanitizer.extractJsonObject(s, 0)).isEqualTo(s);
        }

        @Test
        void braceInStringValue() {
            String s = "{\"k\": \"has {brace}\"}";
            assertThat(JsonSanitizer.extractJsonObject(s, 0)).isEqualTo(s);
        }

        @Test
        void escapedQuoteInStringValue() {
            String s = "{\"k\": \"say \\\"hi\\\"\"}";
            assertThat(JsonSanitizer.extractJsonObject(s, 0)).isEqualTo(s);
        }

        @Test
        void unclosedBrace_returnsFromStart() {
            String s = "{\"k\": \"unclosed\"";
            assertThat(JsonSanitizer.extractJsonObject(s, 0)).isEqualTo(s);
        }
    }

    // ── Real-world LLM output simulation ─────────────────────────────────────

    @Test
    @DisplayName("Handles real-world LLM output with preamble + json fence + explanation")
    void sanitize_realWorldLlmOutput_extractsCleanJson() {
        String llmOutput = """
                I've analyzed the requirements. Here is my response:
                
                ```json
                {
                  "business_goal": "Build a Telegram shop bot",
                  "core_features": ["Browse items", "Purchase item"],
                  "edge_cases_and_handling": [{"case": "empty balance", "solution": "return 402"}],
                  "performance_constraints": ["< 500ms response"]
                }
                ```
                
                This specification covers the core requirements discussed.
                """;

        String result = JsonSanitizer.sanitize(llmOutput);
        assertThat(result).startsWith("{");
        assertThat(result).contains("business_goal");
        assertThat(result).contains("Telegram shop bot");
    }
}
