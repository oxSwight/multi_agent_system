package com.midas.d3.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the packaged golden-set as a first-class data artifact — a typo here would otherwise
 * only surface mid-eval-run (after the backend is up and tokens are being spent). Network-free, so
 * it runs in the normal {@code clean test} suite.
 */
class GoldenSetTest {

    private static final Set<String> VALID_EXPECTED = Set.of("PASS", "DEGRADE");

    @Test
    void classpathGoldenSetIsWellFormedAndUnique() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/eval/golden-set.json")) {
            assertNotNull(in, "classpath:eval/golden-set.json must exist");
            root = mapper.readTree(in);
        }

        List<EvalIdea> ideas = EvalIdea.load(root);
        assertFalse(ideas.isEmpty(), "golden-set must contain at least one idea");

        Set<String> ids = new HashSet<>();
        for (EvalIdea idea : ideas) {
            assertTrue(idea.id() != null && !idea.id().isBlank(), "each idea needs an id");
            assertTrue(ids.add(idea.id()), () -> "duplicate idea id: " + idea.id());
            assertTrue(idea.prompt() != null && !idea.prompt().isBlank(),
                    () -> "idea '" + idea.id() + "' needs a non-blank prompt");
            assertTrue(idea.kind() != null && !idea.kind().isBlank(),
                    () -> "idea '" + idea.id() + "' needs a kind");
            assertTrue(VALID_EXPECTED.contains(idea.expected()),
                    () -> "idea '" + idea.id() + "' has invalid expected='" + idea.expected()
                            + "' (must be PASS or DEGRADE)");
        }
    }
}
