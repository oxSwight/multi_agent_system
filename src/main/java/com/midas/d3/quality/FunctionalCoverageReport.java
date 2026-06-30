package com.midas.d3.quality;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The <b>deterministic functional-coverage evidence</b> for a generated artifact set — the proof that
 * turns the Controller's intent review from "infer coverage from file names" into "confirm coverage
 * from a machine-checked report". For every acceptance criterion (the model-independent
 * {@link DomainCriteriaFloor domain floor} + the model's own {@code must_exist}/{@code must_contain}
 * criteria) it reports whether the implementation evidences it, and which file does.
 *
 * <h2>Relationship to the F2 gate</h2>
 * This is the read-side companion of {@link com.midas.d3.validation.FunctionalCompletenessValidator}:
 * it classifies criteria identically — domain floor and model {@code must_exist} are <em>gated</em>
 * (robust signals), model {@code must_contain} content regexes are <em>advisory</em> — and decides
 * satisfaction through the same {@link RubricRule} the gate uses, so the report and the gate can never
 * disagree on a criterion. The gate consumes only the gated/unmet subset to fail the build; this
 * report exposes the full picture (including the evidencing file) for the Controller to cite.
 */
public final class FunctionalCoverageReport {

    public enum Status { SATISFIED, UNMET, NOT_EVALUABLE }

    /**
     * One evaluated criterion. {@code gated} marks a robust signal the F2 gate hard-enforces;
     * {@code evidenceFile} is the first file that satisfies it (blank when unmet/not-evaluable).
     */
    public record Entry(String id, String requirement, boolean gated, Status status, String evidenceFile) {}

    private FunctionalCoverageReport() {
    }

    /** Evaluates every floor + model criterion of {@code technicalSpec} against {@code sourceFiles}. */
    public static List<Entry> evaluate(JsonNode sourceFiles, JsonNode technicalSpec) {
        // Identical classification to FunctionalCompletenessValidator: floor + model must_exist are
        // the robust (gated) signals; model must_contain content regexes are advisory.
        Map<String, AcceptanceCriterion> gated = new LinkedHashMap<>();
        for (AcceptanceCriterion floor : DomainCriteriaFloor.forSpec(technicalSpec)) {
            gated.putIfAbsent(floor.id(), floor);
        }
        List<AcceptanceCriterion> advisory = new ArrayList<>();
        for (AcceptanceCriterion criterion : AcceptanceCriterion.fromSpec(technicalSpec)) {
            if (criterion.kind() == AcceptanceCriterion.Kind.PATH) {
                gated.putIfAbsent(criterion.id(), criterion);
            } else if (!gated.containsKey(criterion.id())) {
                advisory.add(criterion);
            }
        }

        List<Entry> entries = new ArrayList<>();
        for (AcceptanceCriterion criterion : gated.values()) {
            entries.add(toEntry(criterion, true, sourceFiles));
        }
        for (AcceptanceCriterion criterion : advisory) {
            entries.add(toEntry(criterion, false, sourceFiles));
        }
        return entries;
    }

    private static Entry toEntry(AcceptanceCriterion criterion, boolean gated, JsonNode sourceFiles) {
        return criterion.toRubricRule()
                .map(rule -> {
                    boolean satisfied = rule.evaluate(sourceFiles).isEmpty();
                    return new Entry(criterion.id(), criterion.description(), gated,
                            satisfied ? Status.SATISFIED : Status.UNMET,
                            satisfied ? firstEvidence(criterion, sourceFiles) : "");
                })
                .orElseGet(() -> new Entry(criterion.id(), criterion.description(), gated,
                        Status.NOT_EVALUABLE, ""));
    }

    /**
     * The first file that evidences {@code criterion}, mirroring {@link RubricRule}'s match: for a
     * CONTENT criterion, the first file whose path ends with {@code pathSuffix} and whose body matches
     * the pattern; for a PATH criterion, the first file whose path ends with the required suffix.
     */
    private static String firstEvidence(AcceptanceCriterion criterion, JsonNode sourceFiles) {
        if (sourceFiles == null || !sourceFiles.isObject()) {
            return "";
        }
        boolean pathKind = criterion.kind() == AcceptanceCriterion.Kind.PATH;
        String suffix = normalize(pathKind ? criterion.pattern() : criterion.pathSuffix());
        Pattern contentPattern = pathKind ? null : safeCompile(criterion.pattern());
        if (!pathKind && contentPattern == null) {
            return "";
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = sourceFiles.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String key = normalize(entry.getKey());
            if (!key.endsWith(suffix)) {
                continue;
            }
            if (pathKind) {
                return entry.getKey();
            }
            JsonNode value = entry.getValue();
            if (value != null && value.isTextual() && contentPattern.matcher(value.asText()).find()) {
                return entry.getKey();
            }
        }
        return "";
    }

    private static Pattern safeCompile(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String normalize(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }
}
