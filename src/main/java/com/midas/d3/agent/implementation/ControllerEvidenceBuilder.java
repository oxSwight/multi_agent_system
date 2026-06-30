package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.midas.d3.quality.FunctionalCoverageReport;

/**
 * Builds the deterministic {@code implementationEvidence} digest the Controller (PRODUCT_REVIEW)
 * reviews — the "proof" half of F3, kept strictly at CAPABILITY altitude.
 *
 * <h2>Why capability-level only</h2>
 * The Controller is a coarse intent-conformance gate, not a code reviewer: detail-level correctness
 * (which validation annotations, which error codes, internal control flow) is already owned by the
 * earlier deterministic validators (F2 + the structural gates). An earlier iteration also fed the
 * Controller a per-file symbol digest; live runs showed a signatures-only digest pushes the LLM gate
 * to code-review altitude, where it nitpicks body/field details it cannot see (e.g. field-level
 * {@code @NotBlank}, 404 logic in method bodies) and false-rejects sound products. So this digest
 * carries only {@link FunctionalCoverageReport capability-level functional coverage}.
 *
 * <h2>What it carries</h2>
 * Every robust (gated) criterion — the model-independent domain floor + model {@code must_exist}
 * checks — with its SATISFIED/UNMET status and evidencing file, plus model criteria confirmed
 * SATISFIED. Unreliable advisory UNMET signals (brittle model content regexes F2 refuses to gate on)
 * are suppressed: their absence is NOT evidence of a gap. Pure and deterministic, so it rides the
 * Controller's cacheable prompt prefix without perturbing the prompt-cache contract.
 */
public final class ControllerEvidenceBuilder {

    private ControllerEvidenceBuilder() {
    }

    public static JsonNode build(JsonNode generatedSourceCode,
                                 JsonNode technicalSpec,
                                 ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode coverage = root.putArray("functional_coverage");
        for (FunctionalCoverageReport.Entry entry :
                FunctionalCoverageReport.evaluate(generatedSourceCode, technicalSpec)) {
            // Surface only RELIABLE signals to the blocking gate: every gated (robust) criterion at any
            // status, plus advisory model criteria ONLY when SATISFIED (a true positive). An advisory
            // UNMET is the brittle-regex false-negative F2 deliberately refuses to gate on.
            if (!entry.gated() && entry.status() != FunctionalCoverageReport.Status.SATISFIED) {
                continue;
            }
            ObjectNode node = coverage.addObject();
            node.put("id", entry.id());
            node.put("status", entry.status().name());
            node.put("gated", entry.gated());
            if (entry.requirement() != null && !entry.requirement().isBlank()) {
                node.put("requirement", entry.requirement());
            }
            if (!entry.evidenceFile().isBlank()) {
                node.put("evidence_file", entry.evidenceFile());
            }
        }
        return root;
    }
}
