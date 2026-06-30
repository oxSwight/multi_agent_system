package com.midas.d3.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The <b>deterministic, model-independent functional floor</b> for known product shapes — the
 * mechanism that turns a domain blueprint ("a resume-autofill browser extension with a slide-out
 * sidebar MUST have a floating capsule, a profile dropdown, a Fill button, confidence highlighting,
 * a semantic page scan, and a call to the matching endpoint") from a suggestion the model may ignore
 * into a machine-enforced requirement.
 *
 * <h2>Why this is a floor, not a ceiling</h2>
 * The model still does the rich, product-specific decomposition (carried as
 * {@code acceptance_criteria} in the spec). This class only guarantees the universal-per-shape
 * minimums, so a structurally-passing-but-hollow artifact (the empty popup with a dead "Fill" button)
 * can no longer read as done. The floor is keyed on a <em>precisely detected</em> shape and uses
 * tolerant markers, so it constrains exactly the targeted product and never false-rejects an
 * unrelated one: an unrecognized shape yields an empty floor.
 *
 * <h2>Two consumers, one source</h2>
 * <ul>
 *   <li>{@link #enrich(JsonNode, ObjectMapper)} folds the floor into the stored technical spec at
 *       analysis time, so the criteria also reach the implementation prompt and <em>drive</em>
 *       generation (determinism over tokens) — not merely gate it after the fact.</li>
 *   <li>{@link FunctionalCompletenessValidator} re-applies the floor at the code-generation gate,
 *       idempotently (dedup by id), so the guarantee holds even on a path where enrichment did not
 *       run.</li>
 * </ul>
 */
public final class DomainCriteriaFloor {

    private DomainCriteriaFloor() {
    }

    /** The deterministic floor criteria implied by {@code technicalSpec}'s runtime shape. */
    public static List<AcceptanceCriterion> forSpec(JsonNode technicalSpec) {
        if (technicalSpec == null || !technicalSpec.isObject()) {
            return List.of();
        }
        // contains, not equals: a HYBRID product declares a COMPOUND target such as
        // "BROWSER_EXTENSION + CLOUD_SERVICE", so an exact match would miss exactly the case the
        // floor exists for.
        if (!deploymentTarget(technicalSpec).contains("BROWSER_EXTENSION")) {
            return List.of();
        }

        List<AcceptanceCriterion> floor = new ArrayList<>(browserExtensionBaseline());
        if (looksLikeAutofillSidebar(technicalSpec)) {
            floor.addAll(autofillSidebarFloor());
        }
        return floor;
    }

    /**
     * Returns a copy of {@code technicalSpec} with the deterministic floor merged into its top-level
     * {@code ux_acceptance_criteria} (dedup by id, model-authored entries win on id collision), or the
     * original node unchanged when no floor applies. The enriched spec flows downstream verbatim, so
     * the implementation agent sees the required criteria as part of its prompt.
     */
    public static JsonNode enrich(JsonNode technicalSpec, ObjectMapper mapper) {
        List<AcceptanceCriterion> floor = forSpec(technicalSpec);
        if (floor.isEmpty() || technicalSpec == null || !technicalSpec.isObject()) {
            return technicalSpec;
        }

        ObjectNode copy = technicalSpec.deepCopy();
        ArrayNode ux = copy.has("ux_acceptance_criteria") && copy.get("ux_acceptance_criteria").isArray()
                ? (ArrayNode) copy.get("ux_acceptance_criteria")
                : copy.putArray("ux_acceptance_criteria");

        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode existing : ux) {
            if (existing.isObject() && existing.hasNonNull("id")) {
                byId.put(existing.get("id").asText().strip(), existing);
            }
        }
        for (AcceptanceCriterion criterion : floor) {
            if (byId.containsKey(criterion.id())) {
                continue; // a model-authored criterion already covers this id — do not duplicate.
            }
            ux.add(toJson(criterion, mapper));
        }
        return copy;
    }

    private static ObjectNode toJson(AcceptanceCriterion criterion, ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", criterion.id());
        node.put("description", criterion.description());
        if (criterion.kind() == AcceptanceCriterion.Kind.PATH) {
            node.put("must_exist", criterion.pattern());
        } else {
            node.put("must_contain", criterion.pattern());
            if (!criterion.pathSuffix().isBlank()) {
                node.put("in_file", criterion.pathSuffix());
            }
        }
        return node;
    }

    // ── Floor definitions ────────────────────────────────────────────────────────

    /** Universal minimum for any browser extension: it must wire at least one real user action. */
    private static List<AcceptanceCriterion> browserExtensionBaseline() {
        return List.of(
                AcceptanceCriterion.content("ext-event-handler",
                        "at least one user-action handler (addEventListener / onclick) is wired",
                        ".js", "(?i)addeventlistener\\s*\\(|onclick"));
    }

    /**
     * The rich UX floor for a form-autofill extension with a slide-out sidebar. Markers are tolerant
     * (case-insensitive, multiple accepted phrasings) so a correct implementation that names things
     * reasonably still satisfies them, while a hollow popup fails every one.
     */
    private static List<AcceptanceCriterion> autofillSidebarFloor() {
        List<AcceptanceCriterion> floor = new ArrayList<>();
        floor.add(AcceptanceCriterion.content("ux-sidebar",
                "slide-out sidebar container (not a centered popup window)",
                "", "(?i)side-?bar"));
        floor.add(AcceptanceCriterion.content("ux-capsule",
                "floating capsule / launcher anchored to the page edge that opens the sidebar",
                "", "(?i)capsule|floating|launcher|fab[-_ ]|trigger[-_]?(button|btn)|edge[-_]?(tab|handle)"));
        floor.add(AcceptanceCriterion.content("ux-profile-dropdown",
                "multi-profile selector (dropdown of resumes)",
                "", "(?i)<select|role=[\"']listbox[\"']|profile[-_]?(select|dropdown|picker|switch)"));
        floor.add(AcceptanceCriterion.content("ux-fill-button",
                "primary \"Fill form\" action button",
                "", "(?i)fill[-_ ]?form|fill[-_]?button|btn[-_]?fill|заполнить"));
        floor.add(AcceptanceCriterion.content("ux-highlight-confident",
                "confident-field highlight (green) on filled inputs",
                "", "(?i)confident|highlight[-_]?green|field[-_]?(ok|sure)|\\bgreen\\b"));
        floor.add(AcceptanceCriterion.content("ux-highlight-uncertain",
                "uncertain-field highlight (yellow) with an interactive suggestion prompt",
                "", "(?i)uncertain|low[-_]?confidence|highlight[-_]?yellow|needs[-_]?review|\\byellow\\b"));
        floor.add(AcceptanceCriterion.content("cs-semantic-scan",
                "content script builds a semantic field map of the page (querySelectorAll of inputs)",
                "", "(?i)queryselectorall\\s*\\("));
        floor.add(AcceptanceCriterion.content("cs-semantic-signals",
                "field map reads placeholder / label / aria-label signals",
                "", "(?i)placeholder|aria-label|<label|getattribute\\s*\\(\\s*[\"']aria"));
        floor.add(AcceptanceCriterion.content("api-matching-call",
                "client calls the AI matching endpoint (fetch to the backend)",
                "", "(?i)fetch\\s*\\("));
        return floor;
    }

    // ── Shape detection ──────────────────────────────────────────────────────────

    private static String deploymentTarget(JsonNode technicalSpec) {
        JsonNode env = technicalSpec.get("runtime_environment");
        if (env == null || !env.isObject()) {
            return "";
        }
        JsonNode target = env.get("deployment_target");
        return target != null && target.isTextual()
                ? target.asText().strip().toUpperCase(Locale.ROOT)
                : "";
    }

    /**
     * True when the spec describes a form-autofill / sidebar product. Scans the durable intent text
     * (business goal + feature names) for strong, specific signals so the rich floor attaches only to
     * the targeted shape.
     */
    private static boolean looksLikeAutofillSidebar(JsonNode technicalSpec) {
        String haystack = intentText(technicalSpec).toLowerCase(Locale.ROOT);
        return haystack.matches("(?s).*(sidebar|side-bar|capsule|autofill|auto-fill|auto fill"
                + "|fill.{0,12}form|form.{0,12}fill|resume|cv|profile|заполн|резюме|анкет|сайдбар|капсул).*");
    }

    private static String intentText(JsonNode technicalSpec) {
        StringBuilder sb = new StringBuilder();
        JsonNode goal = technicalSpec.get("business_goal");
        if (goal != null && goal.isTextual()) {
            sb.append(goal.asText()).append('\n');
        }
        JsonNode features = technicalSpec.get("core_features");
        if (features != null && features.isArray()) {
            for (JsonNode feature : features) {
                if (feature.isTextual()) {
                    sb.append(feature.asText()).append('\n');
                } else if (feature.isObject()) {
                    appendText(sb, feature.get("name"));
                    appendText(sb, feature.get("id"));
                }
            }
        }
        return sb.toString();
    }

    private static void appendText(StringBuilder sb, JsonNode node) {
        if (node != null && node.isTextual()) {
            sb.append(node.asText()).append('\n');
        }
    }
}
