package com.midas.d3.agent.implementation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AssembledHealingSupport")
class AssembledHealingSupportTest {

    @Test
    @DisplayName("offendingPaths picks bracketed tokens that are real generated paths, ignoring quoted ids")
    void offendingPaths_matchesPathsNotIds() {
        List<String> violations = List.of(
                "[frontend/src/form.js] uses prompt() — forbidden.",
                "Test [frontend/test/x.test.js] queries element id ['phone'] which does not exist.",
                "[frontend/popup.html] references missing script ['ghost.js'].");
        Set<String> keys = Set.of(
                "frontend/src/form.js", "frontend/test/x.test.js", "frontend/popup.html", "frontend/untouched.js");

        List<String> offending = AssembledHealingSupport.offendingPaths(violations, keys);

        assertThat(offending).containsExactlyInAnyOrder(
                "frontend/src/form.js", "frontend/test/x.test.js", "frontend/popup.html");
        assertThat(offending).doesNotContain("frontend/untouched.js"); // 'phone'/'ghost.js' never match a key
    }

    @Test
    @DisplayName("offendingPaths is empty when no bracket token is a generated path")
    void offendingPaths_emptyWhenNoMatch() {
        List<String> offending = AssembledHealingSupport.offendingPaths(
                List.of("Array 'coverage' must have at least 1 item [hint]."), Set.of("a.js", "b.js"));
        assertThat(offending).isEmpty();
    }

    @Test
    @DisplayName("offendingPaths normalizes backslash paths")
    void offendingPaths_normalizesSeparators() {
        List<String> offending = AssembledHealingSupport.offendingPaths(
                List.of("[frontend\\src\\a.js] bad"), Set.of("frontend/src/a.js"));
        assertThat(offending).containsExactly("frontend/src/a.js");
    }

    @Test
    @DisplayName("healingFeedback enumerates every violation for the regeneration prompt")
    void healingFeedback_listsViolations() {
        String feedback = AssembledHealingSupport.healingFeedback(
                List.of("violation one", "violation two"));
        assertThat(feedback)
                .contains("CROSS-FILE ASSEMBLY CHECK FAILED")
                .contains("violation one")
                .contains("violation two");
    }
}
