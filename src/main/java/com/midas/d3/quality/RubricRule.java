package com.midas.d3.quality;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A single deterministic, LLM-free check applied to a generated artifact set — a path→contents
 * object, the same shape the pipeline's source maps use. {@link #evaluate(JsonNode)} returns a
 * violation message when the rule is unsatisfied, or empty when it passes. Pure and side-effect-free,
 * so a rubric verdict is fully reproducible and CI-stable (no model in the loop).
 */
public final class RubricRule {

    private final String id;
    private final Function<JsonNode, Optional<String>> check;

    private RubricRule(String id, Function<JsonNode, Optional<String>> check) {
        this.id = Objects.requireNonNull(id, "id");
        this.check = Objects.requireNonNull(check, "check");
    }

    public String id() {
        return id;
    }

    /** @return empty when satisfied, otherwise a human-readable violation message. */
    public Optional<String> evaluate(JsonNode artifacts) {
        if (artifacts == null || !artifacts.isObject()) {
            return Optional.of("no artifact object to evaluate");
        }
        return check.apply(artifacts);
    }

    // ── Factories ──────────────────────────────────────────────────────────────

    /** Passes when some artifact path ends with {@code pathSuffix} (separator-insensitive). */
    public static RubricRule requirePath(String pathSuffix) {
        String suffix = normalize(pathSuffix);
        return new RubricRule("requirePath:" + pathSuffix, artifacts ->
                anyKeyMatches(artifacts, key -> normalize(key).endsWith(suffix))
                        ? Optional.empty()
                        : Optional.of("no artifact path ends with '" + pathSuffix + "'"));
    }

    /** Passes when NO artifact's contents match {@code regex} (e.g. a hardcoded secret). */
    public static RubricRule forbidContent(String label, String regex) {
        Pattern pattern = Pattern.compile(regex);
        return new RubricRule("forbidContent:" + label, artifacts -> {
            for (Iterator<Map.Entry<String, JsonNode>> it = artifacts.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                if (v != null && v.isTextual() && pattern.matcher(v.asText()).find()) {
                    return Optional.of("forbidden pattern [" + label + "] found in '" + e.getKey() + "'");
                }
            }
            return Optional.empty();
        });
    }

    /** Passes when at least one artifact whose path ends with {@code pathSuffix} contains {@code regex}. */
    public static RubricRule requireContent(String pathSuffix, String label, String regex) {
        String suffix = normalize(pathSuffix);
        Pattern pattern = Pattern.compile(regex);
        return new RubricRule("requireContent:" + label, artifacts -> {
            for (Iterator<Map.Entry<String, JsonNode>> it = artifacts.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (normalize(e.getKey()).endsWith(suffix)) {
                    JsonNode v = e.getValue();
                    if (v != null && v.isTextual() && pattern.matcher(v.asText()).find()) {
                        return Optional.empty();
                    }
                }
            }
            return Optional.of("no artifact ending '" + pathSuffix + "' contains [" + label + "]");
        });
    }

    private static boolean anyKeyMatches(JsonNode artifacts, Predicate<String> predicate) {
        for (Iterator<String> it = artifacts.fieldNames(); it.hasNext(); ) {
            if (predicate.test(it.next())) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }
}
