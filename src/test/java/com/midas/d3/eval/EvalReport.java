package com.midas.d3.eval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure aggregation + rendering over a list of {@link EvalRunResult}. Performs <b>no I/O</b> — the
 * harness's network layer produces the results, this class turns them into the report the owner
 * reads. Kept side-effect-free so the aggregation logic is covered by a regular unit test that runs
 * in {@code clean test} with no backend.
 *
 * <p>Answers the Phase-0 acceptance question in one artifact: <em>"N ideas: X% COMPLETED, breakdown
 * by kind + stage, $ and seconds per run, list of failures with reasons."</em>
 */
public final class EvalReport {

    private final List<EvalRunResult> results;
    private final String baseUrl;
    private final String observedModel;
    private final long generatedAtEpochMs;

    public EvalReport(List<EvalRunResult> results, String baseUrl, String observedModel, long generatedAtEpochMs) {
        this.results = List.copyOf(results);
        this.baseUrl = baseUrl;
        this.observedModel = observedModel == null || observedModel.isBlank() ? "unknown" : observedModel;
        this.generatedAtEpochMs = generatedAtEpochMs;
    }

    // ── Headline aggregates ──────────────────────────────────────────────────

    public int total() {
        return results.size();
    }

    public long count(EvalOutcome outcome) {
        return results.stream().filter(r -> r.outcome() == outcome).count();
    }

    /** % that reached full COMPLETED — the headline reliability number. */
    public double passRate() {
        return total() == 0 ? 0.0 : (double) count(EvalOutcome.PASS) / total();
    }

    /** % that delivered <em>anything</em> usable (full or gracefully degraded) — the "never crash the client" number. */
    public double deliveredRate() {
        long delivered = results.stream().filter(r -> r.outcome().isDelivered()).count();
        return total() == 0 ? 0.0 : (double) delivered / total();
    }

    /** % that matched their golden-set expectation (PASS-expected fully passed; DEGRADE-expected was delivered). */
    public double expectationRate() {
        long met = results.stream().filter(EvalRunResult::metExpectation).count();
        return total() == 0 ? 0.0 : (double) met / total();
    }

    public long totalTokens() {
        return results.stream().mapToLong(EvalRunResult::totalTokens).sum();
    }

    public double totalCostUsd() {
        return results.stream().filter(r -> r.costUsd() != null).mapToDouble(EvalRunResult::costUsd).sum();
    }

    public double avgCostUsd() {
        return total() == 0 ? 0.0 : totalCostUsd() / total();
    }

    public double avgLatencySeconds() {
        return results.isEmpty() ? 0.0
                : results.stream().mapToDouble(EvalRunResult::latencySeconds).average().orElse(0.0);
    }

    // ── Per-kind breakdown ───────────────────────────────────────────────────

    /** Reliability per scope-kind — we sell a narrow reliable scope, so it must be visible separately. */
    public Map<String, KindStats> perKind() {
        Map<String, KindStats> map = new TreeMap<>();
        for (EvalRunResult r : results) {
            map.computeIfAbsent(r.kind(), k -> new KindStats()).add(r);
        }
        return map;
    }

    /**
     * Where <em>non-delivered</em> runs died — the stage-failure histogram (stage → count). A
     * gracefully-degraded {@link EvalOutcome#PARTIAL} run is delivered, not a failure, so it is
     * excluded; this histogram answers "where does reliability actually break down?".
     */
    public Map<String, Long> stageFailureHistogram() {
        Map<String, Long> hist = new TreeMap<>();
        for (EvalRunResult r : results) {
            if (r.outcome().isDelivered()) continue;
            String stage = r.stageReached() == null || r.stageReached().isBlank() ? "UNKNOWN" : r.stageReached();
            hist.merge(stage, 1L, Long::sum);
        }
        return hist;
    }

    /** Runs that delivered nothing usable (FAIL / TIMEOUT / CLIENT_ERROR) — the "failures with reasons" list. */
    public List<EvalRunResult> failures() {
        List<EvalRunResult> out = new ArrayList<>();
        for (EvalRunResult r : results) {
            if (!r.outcome().isDelivered()) out.add(r);
        }
        return out;
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    /** Machine-readable structure for {@code report.json}. Uses ordered maps so the file is stable. */
    public Map<String, Object> toJsonMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("generatedAt", Instant.ofEpochMilli(generatedAtEpochMs).toString());
        meta.put("baseUrl", baseUrl);
        meta.put("observedModel", observedModel);
        root.put("meta", meta);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total());
        summary.put("pass", count(EvalOutcome.PASS));
        summary.put("partial", count(EvalOutcome.PARTIAL));
        summary.put("fail", count(EvalOutcome.FAIL));
        summary.put("timeout", count(EvalOutcome.TIMEOUT));
        summary.put("clientError", count(EvalOutcome.CLIENT_ERROR));
        summary.put("passRate", round(passRate(), 4));
        summary.put("deliveredRate", round(deliveredRate(), 4));
        summary.put("expectationRate", round(expectationRate(), 4));
        summary.put("totalTokens", totalTokens());
        summary.put("totalCostUsd", round(totalCostUsd(), 6));
        summary.put("avgCostUsd", round(avgCostUsd(), 6));
        summary.put("avgLatencySeconds", round(avgLatencySeconds(), 1));
        root.put("summary", summary);

        Map<String, Object> byKind = new LinkedHashMap<>();
        perKind().forEach((kind, s) -> {
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("total", s.total);
            k.put("pass", s.pass);
            k.put("partial", s.partial);
            k.put("fail", s.fail);
            k.put("timeout", s.timeout);
            k.put("clientError", s.clientError);
            k.put("passRate", round(s.passRate(), 4));
            k.put("deliveredRate", round(s.deliveredRate(), 4));
            k.put("avgCostUsd", round(s.avgCostUsd(), 6));
            k.put("avgLatencySeconds", round(s.avgLatencySeconds(), 1));
            byKind.put(kind, k);
        });
        root.put("byKind", byKind);

        root.put("stageFailureHistogram", new LinkedHashMap<>(stageFailureHistogram()));

        List<Map<String, Object>> failures = new ArrayList<>();
        for (EvalRunResult r : failures()) {
            failures.add(runToMap(r));
        }
        root.put("failures", failures);

        List<Map<String, Object>> runs = new ArrayList<>();
        for (EvalRunResult r : results) {
            runs.add(runToMap(r));
        }
        root.put("runs", runs);
        return root;
    }

    private Map<String, Object> runToMap(EvalRunResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ideaId", r.ideaId());
        m.put("kind", r.kind());
        m.put("expected", r.expected());
        m.put("runId", r.runId());
        m.put("terminalState", r.terminalState());
        m.put("outcome", r.outcome().name());
        m.put("metExpectation", r.metExpectation());
        m.put("stageReached", r.stageReached());
        m.put("latencySeconds", round(r.latencySeconds(), 1));
        m.put("promptTokens", r.promptTokens());
        m.put("completionTokens", r.completionTokens());
        m.put("costUsd", r.costUsd());
        m.put("qualityOverall", r.qualityOverall());
        m.put("verdict", r.verdict());
        m.put("remediationAttempts", r.remediationAttempts());
        m.put("failureReason", r.failureReason());
        return m;
    }

    /** Human-readable {@code report.md}. */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# MIDAS Eval Report\n\n");
        sb.append("- Generated: ").append(Instant.ofEpochMilli(generatedAtEpochMs)).append('\n');
        sb.append("- Backend: `").append(baseUrl).append("`  ·  Observed model: `").append(observedModel).append("`\n\n");

        sb.append("## Summary\n\n");
        sb.append(String.format("**%d ideas — %.0f%% COMPLETED** (delivered incl. degraded: %.0f%%, met-expectation: %.0f%%)%n%n",
                total(), passRate() * 100, deliveredRate() * 100, expectationRate() * 100));
        sb.append(String.format("| PASS | PARTIAL | FAIL | TIMEOUT | CLIENT_ERROR |%n"));
        sb.append("|---:|---:|---:|---:|---:|\n");
        sb.append(String.format("| %d | %d | %d | %d | %d |%n%n",
                count(EvalOutcome.PASS), count(EvalOutcome.PARTIAL), count(EvalOutcome.FAIL),
                count(EvalOutcome.TIMEOUT), count(EvalOutcome.CLIENT_ERROR)));
        sb.append(String.format("Economics: **$%.4f total** (avg $%.4f/run), %,d tokens, avg %.1fs/run.%n%n",
                totalCostUsd(), avgCostUsd(), totalTokens(), avgLatencySeconds()));

        sb.append("## By kind\n\n");
        sb.append("| Kind | N | PASS | delivered% | pass% | avg $ | avg s |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|\n");
        perKind().forEach((kind, s) -> sb.append(String.format("| %s | %d | %d | %.0f%% | %.0f%% | $%.4f | %.1f |%n",
                kind, s.total, s.pass, s.deliveredRate() * 100, s.passRate() * 100, s.avgCostUsd(), s.avgLatencySeconds())));
        sb.append('\n');

        sb.append("## Stage-failure histogram\n\n");
        Map<String, Long> hist = stageFailureHistogram();
        if (hist.isEmpty()) {
            sb.append("_No non-passing runs._\n\n");
        } else {
            sb.append("| Stage reached | Failures |\n|---|---:|\n");
            hist.forEach((stage, n) -> sb.append(String.format("| %s | %d |%n", stage, n)));
            sb.append('\n');
        }

        sb.append("## Failures\n\n");
        List<EvalRunResult> failures = failures();
        if (failures.isEmpty()) {
            sb.append("_None._\n");
        } else {
            sb.append("| Idea | Kind | Outcome | Stage | Reason |\n|---|---|---|---|---|\n");
            for (EvalRunResult r : failures) {
                sb.append(String.format("| %s | %s | %s | %s | %s |%n",
                        r.ideaId(), r.kind(), r.outcome(), nvl(r.stageReached()),
                        truncate(nvl(r.failureReason()), 160).replace("|", "\\|").replace("\n", " ")));
            }
        }
        return sb.toString();
    }

    /** Compact console summary printed at the end of a run. */
    public String toConsole() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n============ MIDAS EVAL ============\n");
        sb.append(String.format("%d ideas — %.0f%% COMPLETED  (delivered %.0f%%, met-expectation %.0f%%)%n",
                total(), passRate() * 100, deliveredRate() * 100, expectationRate() * 100));
        sb.append(String.format("PASS=%d PARTIAL=%d FAIL=%d TIMEOUT=%d CLIENT_ERROR=%d%n",
                count(EvalOutcome.PASS), count(EvalOutcome.PARTIAL), count(EvalOutcome.FAIL),
                count(EvalOutcome.TIMEOUT), count(EvalOutcome.CLIENT_ERROR)));
        sb.append(String.format("$%.4f total, %,d tokens, avg %.1fs/run%n", totalCostUsd(), totalTokens(), avgLatencySeconds()));
        perKind().forEach((kind, s) -> sb.append(String.format("  %-16s %d/%d PASS (%.0f%% delivered)%n",
                kind, s.pass, s.total, s.deliveredRate() * 100)));
        if (!failures().isEmpty()) {
            sb.append("Failures:\n");
            for (EvalRunResult r : failures()) {
                sb.append(String.format("  - %s [%s] %s @ %s: %s%n",
                        r.ideaId(), r.kind(), r.outcome(), nvl(r.stageReached()), truncate(nvl(r.failureReason()), 120)));
            }
        }
        sb.append("====================================\n");
        return sb.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static double round(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }

    /** Mutable per-kind accumulator. */
    public static final class KindStats {
        public int total, pass, partial, fail, timeout, clientError;
        private double costSum;
        private int costCount;
        private double latencySecSum;

        void add(EvalRunResult r) {
            total++;
            switch (r.outcome()) {
                case PASS -> pass++;
                case PARTIAL -> partial++;
                case FAIL -> fail++;
                case TIMEOUT -> timeout++;
                case CLIENT_ERROR -> clientError++;
            }
            if (r.costUsd() != null) {
                costSum += r.costUsd();
                costCount++;
            }
            latencySecSum += r.latencySeconds();
        }

        public double passRate() {
            return total == 0 ? 0.0 : (double) pass / total;
        }

        public double deliveredRate() {
            return total == 0 ? 0.0 : (double) (pass + partial) / total;
        }

        public double avgCostUsd() {
            return costCount == 0 ? 0.0 : costSum / costCount;
        }

        public double avgLatencySeconds() {
            return total == 0 ? 0.0 : latencySecSum / total;
        }
    }
}
