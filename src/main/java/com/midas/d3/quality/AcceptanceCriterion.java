package com.midas.d3.quality;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A single <b>machine-checkable acceptance criterion</b> for a generated artifact — the unit that
 * makes "schema-valid + loadable" insufficient and "actually implements the requested capability"
 * the bar. It is the functional-fidelity counterpart of the structural {@link RubricRule}: where a
 * rubric rule is an anonymous static check, a criterion carries the human intent it encodes (its
 * {@link #description()}) so a failure can be reported as an actionable, feature-level gap rather
 * than an opaque regex miss.
 *
 * <p>A criterion compiles down to exactly one {@link RubricRule} via {@link #toRubricRule()}:
 * <ul>
 *   <li>{@link Kind#CONTENT} → {@link RubricRule#requireContent(String, String, String)} — at least
 *       one artifact whose path ends with {@code pathSuffix} (empty = any file) contains
 *       {@code pattern}. Used for "a UI element / handler / endpoint-call / DB field must exist".</li>
 *   <li>{@link Kind#PATH} → {@link RubricRule#requirePath(String)} — some artifact path ends with
 *       {@code pattern}. Used for "this file must be generated".</li>
 * </ul>
 *
 * <p>Criteria come from two type-independent sources that are merged before evaluation: the model's
 * semantic decomposition of each feature/UX requirement (carried in the technical spec) and a
 * deterministic, model-independent {@link DomainCriteriaFloor domain floor} for known product
 * shapes. Both feed the same evaluation, so the floor cannot be argued away by a lazy model.
 */
public record AcceptanceCriterion(String id, String description, Kind kind,
                                  String pathSuffix, String pattern) {

    public enum Kind { CONTENT, PATH }

    public AcceptanceCriterion {
        id = (id == null || id.isBlank()) ? "criterion" : id.strip();
        description = (description == null) ? "" : description.strip();
        kind = (kind == null) ? Kind.CONTENT : kind;
        pathSuffix = (pathSuffix == null) ? "" : pathSuffix.strip();
        pattern = (pattern == null) ? "" : pattern.strip();
    }

    public static AcceptanceCriterion content(String id, String description, String pathSuffix, String pattern) {
        return new AcceptanceCriterion(id, description, Kind.CONTENT, pathSuffix, pattern);
    }

    public static AcceptanceCriterion path(String id, String description, String requiredPathSuffix) {
        return new AcceptanceCriterion(id, description, Kind.PATH, "", requiredPathSuffix);
    }

    /**
     * Compiles this criterion into its {@link RubricRule}, or empty when it is unusable — a blank
     * pattern, or a {@code CONTENT} pattern that is not a valid regex. A model-authored criterion
     * with a broken regex must never crash or wedge the pipeline; it is dropped (the floor still
     * applies), keeping the gate fail-safe rather than fail-closed on garbage input.
     */
    public Optional<RubricRule> toRubricRule() {
        if (pattern.isBlank()) {
            return Optional.empty();
        }
        if (kind == Kind.PATH) {
            return Optional.of(RubricRule.requirePath(pattern));
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return Optional.empty();
        }
        return Optional.of(RubricRule.requireContent(pathSuffix, id, pattern));
    }

    /** A precise, feature-level violation message for when this criterion is unmet. */
    public String violationMessage() {
        StringBuilder sb = new StringBuilder("Functional gap [").append(id).append("]");
        if (!description.isBlank()) {
            sb.append(": ").append(description);
        }
        if (kind == Kind.PATH) {
            sb.append(" — no generated file path ends with '").append(pattern).append("'");
        } else if (!pathSuffix.isBlank()) {
            sb.append(" — required marker not found in any '").append(pathSuffix).append("' file");
        } else {
            sb.append(" — required marker not found in any generated source file");
        }
        sb.append(". The spec promises this capability but the implementation does not evidence it; "
                + "implement the element/handler/endpoint that satisfies it.");
        return sb.toString();
    }

    // ── Parsing model-authored criteria out of the technical spec ────────────────

    /**
     * Extracts every model-authored criterion from a technical spec: per-feature criteria under
     * {@code core_features[].acceptance_criteria[]} and product-level UX criteria under the
     * top-level {@code ux_acceptance_criteria[]}. Tolerant by design — unknown shapes, string
     * features, and malformed entries are skipped rather than rejected, because the deterministic
     * floor is the guarantee, not the model's diligence.
     *
     * <p>Accepted entry shapes (all fields optional except a check):
     * <pre>
     *   {"id":"...", "description":"...", "must_contain":"&lt;regex&gt;", "in_file":"&lt;path suffix&gt;"}
     *   {"id":"...", "description":"...", "must_exist":"&lt;path suffix&gt;"}
     * </pre>
     */
    public static List<AcceptanceCriterion> fromSpec(JsonNode technicalSpec) {
        List<AcceptanceCriterion> criteria = new ArrayList<>();
        if (technicalSpec == null || !technicalSpec.isObject()) {
            return criteria;
        }

        JsonNode coreFeatures = technicalSpec.get("core_features");
        if (coreFeatures != null && coreFeatures.isArray()) {
            for (JsonNode feature : coreFeatures) {
                if (!feature.isObject()) {
                    continue;
                }
                String featureId = textOrBlank(feature.get("id"));
                if (featureId.isBlank()) {
                    featureId = textOrBlank(feature.get("name"));
                }
                parseArray(feature.get("acceptance_criteria"), featureId, criteria);
            }
        }

        parseArray(technicalSpec.get("ux_acceptance_criteria"), "ux", criteria);
        return criteria;
    }

    private static void parseArray(JsonNode array, String namespace, List<AcceptanceCriterion> sink) {
        if (array == null || !array.isArray()) {
            return;
        }
        int index = 0;
        for (JsonNode entry : array) {
            index++;
            if (!entry.isObject()) {
                continue;
            }
            String id = qualifiedId(namespace, textOrBlank(entry.get("id")), index);
            String description = textOrBlank(entry.get("description"));

            String mustExist = textOrBlank(entry.get("must_exist"));
            if (!mustExist.isBlank()) {
                sink.add(path(id, description, mustExist));
                continue;
            }
            String mustContain = textOrBlank(entry.get("must_contain"));
            if (!mustContain.isBlank()) {
                sink.add(content(id, description, textOrBlank(entry.get("in_file")), mustContain));
            }
        }
    }

    private static String qualifiedId(String namespace, String id, int index) {
        String base = id.isBlank() ? "c" + index : id;
        String ns = namespace == null ? "" : namespace.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        if (ns.isBlank() || ns.equals("-")) {
            return base;
        }
        return ns + ":" + base;
    }

    private static String textOrBlank(JsonNode node) {
        return node != null && node.isTextual() ? node.asText().strip() : "";
    }
}
