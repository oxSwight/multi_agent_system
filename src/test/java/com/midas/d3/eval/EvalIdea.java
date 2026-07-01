package com.midas.d3.eval;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * One golden-set entry: a representative user idea plus its scope classification and the outcome
 * we expect from it.
 *
 * <ul>
 *   <li>{@code kind} — SERVER_SIDE_CRUD / EXTENSION / HYBRID / STRESS. Drives the per-scope reliability
 *       breakdown (we sell a narrow reliable scope; the report must show it separately).</li>
 *   <li>{@code expected} — {@code PASS} (should fully complete) or {@code DEGRADE} (deliberately
 *       over-scoped; must be gracefully delivered, never a client-visible {@code ERROR}).</li>
 * </ul>
 */
public record EvalIdea(
        String id,
        String kind,
        String title,
        String expected,
        List<String> tags,
        String prompt
) {

    /** Parses the {@code ideas} array of a golden-set document. Reads defensively — unknown/missing
     *  fields fall back to safe defaults rather than throwing. */
    public static List<EvalIdea> load(JsonNode root) {
        List<EvalIdea> ideas = new ArrayList<>();
        for (JsonNode n : root.path("ideas")) {
            List<String> tags = new ArrayList<>();
            for (JsonNode t : n.path("tags")) {
                tags.add(t.asText());
            }
            ideas.add(new EvalIdea(
                    n.path("id").asText(),
                    n.path("kind").asText("UNKNOWN"),
                    n.path("title").asText(""),
                    n.path("expected").asText("PASS"),
                    List.copyOf(tags),
                    n.path("prompt").asText("")
            ));
        }
        return ideas;
    }
}
