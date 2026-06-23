package com.midas.d3.build;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the heap-bounding output containment in isolation. The process-tree kill and real timeout
 * are OS-level and exercised in deployment, not in this deterministic unit suite.
 */
@DisplayName("SystemProcessRunner — output containment")
class SystemProcessRunnerTest {

    private static ByteArrayInputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("small output is captured verbatim")
    void smallOutputVerbatim() {
        StringBuffer out = new StringBuffer();
        SystemProcessRunner.drainCapped(stream("hello\nworld\n"), out);
        assertThat(out.toString()).isEqualTo("hello\nworld\n");
    }

    @Test
    @DisplayName("an output flood is truncated at the cap, bounding heap")
    void cappedOutput() {
        String flood = "x".repeat(SystemProcessRunner.MAX_OUTPUT_CHARS + 50_000);
        StringBuffer out = new StringBuffer();
        SystemProcessRunner.drainCapped(stream(flood), out);

        assertThat(out.length()).isLessThan(SystemProcessRunner.MAX_OUTPUT_CHARS + 100);
        assertThat(out.toString()).endsWith("[...output truncated for sandbox containment]");
    }

    @Test
    @DisplayName("empty stream yields empty output")
    void emptyStream() {
        StringBuffer out = new StringBuffer();
        SystemProcessRunner.drainCapped(stream(""), out);
        assertThat(out.toString()).isEmpty();
    }
}
