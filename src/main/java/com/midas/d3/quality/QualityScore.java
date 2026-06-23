package com.midas.d3.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic quality verdict for one generated artifact set.
 *
 * <p>{@code buildPassed} is a <b>hard gate</b>: a project that does not build scores
 * {@link #overall()} 0 regardless of rubric — non-building output has no usable quality, so a high
 * rubric score on un-buildable code must never read as "good." Among building projects the overall
 * score is the rubric satisfied-fraction.
 */
public record QualityScore(boolean buildPassed, double rubricScore, List<String> rubricViolations) {

    public QualityScore {
        rubricViolations = (rubricViolations == null) ? List.of() : List.copyOf(rubricViolations);
        rubricScore = Math.max(0.0, Math.min(1.0, rubricScore));
    }

    /** Overall quality in [0,1]: 0 when the build fails, otherwise the rubric fraction. */
    public double overall() {
        return buildPassed ? rubricScore : 0.0;
    }

    public JsonNode toJson(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        ObjectNode root = mapper.createObjectNode();
        root.put("build_passed", buildPassed);
        root.put("rubric_score", rubricScore);
        root.put("overall", overall());
        ArrayNode violations = root.putArray("rubric_violations");
        rubricViolations.forEach(violations::add);
        return root;
    }
}
