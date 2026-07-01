package com.midas.d3.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression-safe unit tests for the eval-harness <em>pure logic</em> — outcome classification and
 * report aggregation. No network, no backend: these run in the normal {@code clean test} suite and
 * guard the numbers the owner will base decisions on.
 */
class EvalReportTest {

    // ── classification ────────────────────────────────────────────────────

    @Test
    void classifyMapsTerminalStatesToOutcomes() {
        assertEquals(EvalOutcome.PASS, EvalOutcome.classify("COMPLETED", false, false));
        assertEquals(EvalOutcome.PARTIAL, EvalOutcome.classify("COMPLETED_WITH_GAPS", false, false));
        assertEquals(EvalOutcome.FAIL, EvalOutcome.classify("ERROR", false, false));
        assertEquals(EvalOutcome.TIMEOUT, EvalOutcome.classify("CODE_GENERATION", true, false));
        assertEquals(EvalOutcome.CLIENT_ERROR, EvalOutcome.classify(null, false, true));
        // non-terminal state at deadline without the timedOut flag → treated as TIMEOUT, not PASS
        assertEquals(EvalOutcome.TIMEOUT, EvalOutcome.classify("CODE_GENERATION", false, false));
    }

    @Test
    void isTerminalRecognisesDegradedCompletion() {
        assertTrue(EvalOutcome.isTerminal("COMPLETED"));
        assertTrue(EvalOutcome.isTerminal("COMPLETED_WITH_GAPS"));
        assertTrue(EvalOutcome.isTerminal("ERROR"));
        assertFalse(EvalOutcome.isTerminal("CODE_GENERATION"));
        assertFalse(EvalOutcome.isTerminal("SYSTEM_ANALYSIS"));
    }

    @Test
    void deliveredCoversFullAndPartial() {
        assertTrue(EvalOutcome.PASS.isDelivered());
        assertTrue(EvalOutcome.PARTIAL.isDelivered());
        assertFalse(EvalOutcome.FAIL.isDelivered());
        assertFalse(EvalOutcome.TIMEOUT.isDelivered());
    }

    // ── expectation matching ──────────────────────────────────────────────

    @Test
    void degradeExpectedIsSatisfiedByDeliveryNotByError() {
        assertTrue(result("x", "STRESS", "DEGRADE", EvalOutcome.PARTIAL).metExpectation());
        assertTrue(result("x", "STRESS", "DEGRADE", EvalOutcome.PASS).metExpectation());
        assertFalse(result("x", "STRESS", "DEGRADE", EvalOutcome.FAIL).metExpectation());
        // a PASS-expected idea is only satisfied by a full PASS
        assertTrue(result("x", "SERVER_SIDE_CRUD", "PASS", EvalOutcome.PASS).metExpectation());
        assertFalse(result("x", "SERVER_SIDE_CRUD", "PASS", EvalOutcome.PARTIAL).metExpectation());
    }

    // ── aggregation ────────────────────────────────────────────────────────

    @Test
    void reportAggregatesRatesTokensAndStageHistogram() {
        List<EvalRunResult> results = List.of(
                // 2 CRUD: one pass, one fail at CODE_GENERATION
                pass("srv-a", "SERVER_SIDE_CRUD", 1000, 500, 0.10, 120_000),
                fail("srv-b", "SERVER_SIDE_CRUD", "CODE_GENERATION", "functional gap: ux-capsule", 200_000),
                // 1 extension pass
                pass("ext-a", "EXTENSION", 800, 400, 0.08, 90_000),
                // 1 stress degrade (delivered partial)
                partial("stress-a", "STRESS", 3000, 1500, 0.30, 300_000));

        EvalReport report = new EvalReport(results, "http://localhost:8080", "gpt-4o", 0L);

        assertEquals(4, report.total());
        assertEquals(2, report.count(EvalOutcome.PASS));
        assertEquals(1, report.count(EvalOutcome.PARTIAL));
        assertEquals(1, report.count(EvalOutcome.FAIL));
        assertEquals(0.5, report.passRate(), 1e-9);           // 2/4
        assertEquals(0.75, report.deliveredRate(), 1e-9);     // 3/4 (2 pass + 1 partial)
        // srv-b is PASS-expected but FAILed → misses expectation; the other 3 meet theirs → 3/4
        assertEquals(0.75, report.expectationRate(), 1e-9);

        assertEquals(1000 + 500 + 800 + 400 + 3000 + 1500, report.totalTokens());
        assertEquals(0.10 + 0.08 + 0.30, report.totalCostUsd(), 1e-9);

        // stage-failure histogram counts only NON-DELIVERED runs, keyed by where they stopped —
        // the delivered PARTIAL is excluded (a graceful degradation is not a failure)
        Map<String, Long> hist = report.stageFailureHistogram();
        assertEquals(1, hist.size());
        assertEquals(1L, hist.get("CODE_GENERATION"));
        assertFalse(hist.containsKey("COMPLETED"), "delivered runs must not appear in the failure histogram");

        // per-kind breakdown
        Map<String, EvalReport.KindStats> byKind = report.perKind();
        assertEquals(0.5, byKind.get("SERVER_SIDE_CRUD").passRate(), 1e-9);
        assertEquals(1.0, byKind.get("EXTENSION").passRate(), 1e-9);
        assertEquals(1.0, byKind.get("STRESS").deliveredRate(), 1e-9);

        assertEquals(1, report.failures().size()); // only the FAIL, not the delivered PARTIAL
    }

    @Test
    void markdownAndJsonRenderWithoutThrowing() {
        EvalReport report = new EvalReport(
                List.of(pass("a", "EXTENSION", 100, 50, 0.01, 60_000),
                        fail("b", "HYBRID", "PRODUCT_REVIEW", "controller REJECT", 90_000)),
                "http://localhost:8080", "gpt-4o", 0L);

        String md = report.toMarkdown();
        assertTrue(md.contains("MIDAS Eval Report"));
        assertTrue(md.contains("50% COMPLETED") || md.contains("50 % COMPLETED"));
        assertTrue(md.contains("PRODUCT_REVIEW"), "failure stage should appear in the failures table");

        Map<String, Object> json = report.toJsonMap();
        assertTrue(json.containsKey("summary"));
        assertTrue(json.containsKey("byKind"));
        assertTrue(json.containsKey("stageFailureHistogram"));
        assertTrue(json.containsKey("failures"));
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static EvalRunResult result(String id, String kind, String expected, EvalOutcome outcome) {
        return new EvalRunResult(id, kind, expected, "run-" + id, outcome.name(), outcome,
                outcome == EvalOutcome.PASS ? "COMPLETED" : "CODE_GENERATION",
                60_000, 0, 0, null, null, null, 0, null);
    }

    private static EvalRunResult pass(String id, String kind, int pt, int ct, double cost, long latencyMs) {
        return new EvalRunResult(id, kind, "PASS", "run-" + id, "COMPLETED", EvalOutcome.PASS,
                "COMPLETED", latencyMs, pt, ct, cost, 1.0, "PASS", 0, null);
    }

    private static EvalRunResult partial(String id, String kind, int pt, int ct, double cost, long latencyMs) {
        return new EvalRunResult(id, kind, "DEGRADE", "run-" + id, "COMPLETED_WITH_GAPS", EvalOutcome.PARTIAL,
                "COMPLETED", latencyMs, pt, ct, cost, 0.6, "PASS_WITH_NOTES", 1, null);
    }

    private static EvalRunResult fail(String id, String kind, String stage, String reason, long latencyMs) {
        return new EvalRunResult(id, kind, "PASS", "run-" + id, "ERROR", EvalOutcome.FAIL,
                stage, latencyMs, 0, 0, null, null, null, 0, reason);
    }
}
