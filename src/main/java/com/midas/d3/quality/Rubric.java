package com.midas.d3.quality;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A named, ordered set of deterministic {@link RubricRule}s. Evaluating a rubric against a generated
 * artifact set yields a {@link Result}: the fraction of rules satisfied (in [0,1]) plus the list of
 * violation messages. An empty rubric is vacuously satisfied (score 1.0).
 */
public record Rubric(String name, List<RubricRule> rules) {

    public Rubric {
        Objects.requireNonNull(name, "name");
        rules = (rules == null) ? List.of() : List.copyOf(rules);
    }

    public Result evaluate(JsonNode artifacts) {
        List<String> violations = new ArrayList<>();
        for (RubricRule rule : rules) {
            rule.evaluate(artifacts).ifPresent(msg -> violations.add(rule.id() + " — " + msg));
        }
        double score = rules.isEmpty() ? 1.0 : (double) (rules.size() - violations.size()) / rules.size();
        return new Result(score, List.copyOf(violations));
    }

    /** Outcome of evaluating a rubric: satisfied-fraction in [0,1] and the violation messages. */
    public record Result(double score, List<String> violations) {
        public Result {
            violations = (violations == null) ? List.of() : List.copyOf(violations);
        }
    }
}
