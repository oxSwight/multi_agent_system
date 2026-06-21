package com.midas.d3.validation;

import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QaEngineerValidatorTest {

    private QaEngineerValidator validator;

    @BeforeEach
    void setUp() {
        validator = new QaEngineerValidator(new JacksonConfig().objectMapper());
    }

    @Test
    void validateSingleFileOutput_validMarkdownBlock_returnsContent() throws Exception {
        String content = validator.validateSingleFileOutput("""
                ```typescript
                describe('popup', () => { it('works', () => expect(true).toBe(true)); });
                ```
                """, "src/popup.test.ts");

        assertThat(content).contains("describe('popup'");
    }

    @Test
    void validateSingleFileOutput_missingCodeBlock_throws() {
        assertThatThrownBy(() -> validator.validateSingleFileOutput(
                "describe('popup', () => {})", "src/popup.test.ts"))
                .isInstanceOf(ValidationHookException.class)
                .hasMessageContaining("markdown code block");
    }

    @Test
    void validateSingleFileOutput_jsonEnvelope_throws() {
        assertThatThrownBy(() -> validator.validateSingleFileOutput("""
                {"src/popup.test.ts":"describe('x', () => {})"}
                """, "src/popup.test.ts"))
                .isInstanceOf(ValidationHookException.class)
                .hasMessageContaining("JSON envelope forbidden");
    }

    @Test
    void validate_assembledMap_passes() throws Exception {
        var result = validator.validate("""
                {"src/popup.test.ts":"describe('popup', () => { it('works', () => expect(true).toBe(true)); });"}
                """);

        assertThat(result.has("src/popup.test.ts")).isTrue();
    }
}
