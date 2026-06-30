package com.midas.d3.quality;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a deterministic {@link Rubric} from a technical spec for the runtime quality score (F4).
 *
 * <p>It composes the existing, battle-tested spec→criteria machinery rather than authoring new
 * regexes: the model-independent {@link DomainCriteriaFloor} for the detected product shape plus the
 * model's own {@link AcceptanceCriterion criteria}, each compiled to a {@link RubricRule} via
 * {@link AcceptanceCriterion#toRubricRule()} (which already drops blank/invalid patterns).
 *
 * <h2>Gated criteria only (the F3-safe altitude)</h2>
 * The rubric includes ONLY the robust (gated) signals — the domain floor + model {@code must_exist}
 * checks — exactly the set {@link FunctionalCoverageReport} marks {@code gated}. Brittle model
 * {@code must_contain} content regexes are excluded for the same reason F2 refuses to gate on them and
 * F3 suppresses their UNMET signal: they false-negative and would drag the score down on correct
 * code. Because this shares {@link RubricRule} evaluation with F2/F3, the quality score can never
 * disagree with those gates about whether a criterion is met. An empty rubric scores a vacuous 1.0.
 */
public final class SpecRubricBuilder {

    private SpecRubricBuilder() {
    }

    public static Rubric fromSpec(JsonNode technicalSpec) {
        Map<String, AcceptanceCriterion> gated = new LinkedHashMap<>();
        for (AcceptanceCriterion floor : DomainCriteriaFloor.forSpec(technicalSpec)) {
            gated.putIfAbsent(floor.id(), floor);
        }
        for (AcceptanceCriterion criterion : AcceptanceCriterion.fromSpec(technicalSpec)) {
            if (criterion.kind() == AcceptanceCriterion.Kind.PATH) {
                gated.putIfAbsent(criterion.id(), criterion); // file-existence is a robust signal
            }
            // model must_contain (CONTENT) criteria are advisory — deliberately excluded
        }

        List<RubricRule> rules = new ArrayList<>();
        for (AcceptanceCriterion criterion : gated.values()) {
            criterion.toRubricRule().ifPresent(rules::add);
        }
        return new Rubric("spec", rules);
    }
}
