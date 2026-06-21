package com.midas.d3.llm.nous.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NousResponse Ollama DeepSeek extraction")
class NousResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Prefers content field when present")
    void extractText_contentPresent_returnsContent() throws Exception {
        NousResponse response = objectMapper.readValue("""
                {"choices":[{"message":{"role":"assistant","content":"{\\"verdict\\":\\"PASS\\"}","reasoning":"long thought"},"finish_reason":"stop"}]}
                """, NousResponse.class);

        assertThat(response.extractText()).contains("{\"verdict\":\"PASS\"}");
        assertThat(response.usedReasoningFallback()).isFalse();
    }

    @Test
    @DisplayName("Falls back to reasoning when content is empty (Ollama DeepSeek-R1)")
    void extractText_emptyContent_usesReasoning() throws Exception {
        NousResponse response = objectMapper.readValue("""
                {"choices":[{"message":{"role":"assistant","content":"","reasoning":"{\\"verdict\\":\\"PASS\\"}"},"finish_reason":"stop"}]}
                """, NousResponse.class);

        assertThat(response.extractText()).contains("{\"verdict\":\"PASS\"}");
        assertThat(response.usedReasoningFallback()).isTrue();
    }
}
