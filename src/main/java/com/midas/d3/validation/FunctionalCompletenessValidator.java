package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.midas.d3.quality.AcceptanceCriterion;
import com.midas.d3.quality.DomainCriteriaFloor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The <b>functional-fidelity gate</b> of CODE_GENERATION — the layer that makes "schema-valid and
 * loadable" insufficient and "actually implements the requested capability" the bar. Where the P0
 * gates ({@link ManifestReferenceValidator}, {@link PathConformanceValidator},
 * {@link FrontendIntegrationValidator}) prove the artifact is structurally well-formed, this gate
 * proves it is functionally complete against the product's acceptance criteria.
 *
 * <h2>What is gated, and why not everything</h2>
 * The hard gate is built from <b>robust, deterministic signals only</b>:
 * <ul>
 *   <li>the model-independent {@link DomainCriteriaFloor domain floor} for the detected product
 *       shape — short, tolerant, Java-authored markers (an element class, a {@code querySelectorAll}
 *       call, a {@code fetch}) that a correct implementation satisfies regardless of naming; and</li>
 *   <li>model-authored {@code must_exist} criteria — a required file path is a robust, unambiguous
 *       assertion.</li>
 * </ul>
 * Model-authored {@code must_contain} (content regex) criteria are deliberately <b>advisory, not
 * gated</b>. Live runs showed models write brittle, over-specified patterns — full multi-line code
 * shapes, exact identifiers, single-line {@code .*} that cannot cross newlines — which would
 * false-reject a perfectly correct implementation and wedge the pipeline. They still earn their keep:
 * they are folded into the spec and so <em>drive</em> generation, and their satisfaction is logged as
 * an observability signal. This is the brief's own principle — checkable work is deterministic Java;
 * the model supplies semantics, not the verification regex.
 *
 * <p>Runs only on the fully assembled envelope (its caller gates it on a non-null architecture), so a
 * partial HYBRID surface pass is never judged against the whole-product floor. Fail-safe by
 * construction: an unrecognized shape with no floor and no {@code must_exist} criteria yields zero
 * violations.
 */
final class FunctionalCompletenessValidator {

    private static final Logger log = LoggerFactory.getLogger(FunctionalCompletenessValidator.class);

    private FunctionalCompletenessValidator() {
    }

    static void validate(JsonNode sourceFiles, JsonNode technicalSpec, List<String> violations) {
        if (sourceFiles == null || !sourceFiles.isObject() || sourceFiles.isEmpty()) {
            return;
        }

        Map<String, AcceptanceCriterion> hard = new LinkedHashMap<>();
        for (AcceptanceCriterion floor : DomainCriteriaFloor.forSpec(technicalSpec)) {
            hard.putIfAbsent(floor.id(), floor);
        }

        List<AcceptanceCriterion> advisory = new ArrayList<>();
        for (AcceptanceCriterion criterion : AcceptanceCriterion.fromSpec(technicalSpec)) {
            if (criterion.kind() == AcceptanceCriterion.Kind.PATH) {
                hard.putIfAbsent(criterion.id(), criterion); // file-existence is a robust hard check
            } else if (!hard.containsKey(criterion.id())) {
                advisory.add(criterion); // brittle model content regex — guide generation, do not gate
            }
        }

        for (AcceptanceCriterion criterion : hard.values()) {
            criterion.toRubricRule()
                    .flatMap(rule -> rule.evaluate(sourceFiles))
                    .ifPresent(unused -> violations.add(criterion.violationMessage()));
        }

        logAdvisoryCoverage(advisory, sourceFiles);
    }

    /** Observability only: how many of the model's own content criteria the implementation evidences. */
    private static void logAdvisoryCoverage(List<AcceptanceCriterion> advisory, JsonNode sourceFiles) {
        if (advisory.isEmpty() || !log.isInfoEnabled()) {
            return;
        }
        long unmet = advisory.stream()
                .filter(c -> c.toRubricRule().flatMap(rule -> rule.evaluate(sourceFiles)).isPresent())
                .count();
        if (unmet > 0) {
            log.info("[FunctionalCompleteness] {} of {} model-authored content criteria not yet evidenced "
                    + "(advisory — not gated, as model-written regexes are unreliable).", unmet, advisory.size());
        }
    }
}
