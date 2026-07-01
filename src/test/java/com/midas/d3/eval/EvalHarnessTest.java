package com.midas.d3.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Eval-harness entry point (Phase 0 · "measure reliability first").
 *
 * <p>Drives a versioned golden-set of ideas through a <em>running</em> MIDAS backend over REST,
 * measures per-idea outcome + economics, and writes a report:
 * {@code target/eval-report/report.{json,md}} plus a console summary.
 *
 * <h2>Why a guarded JUnit test rather than a standalone {@code main()}</h2>
 * Surefire's fork launch is known to work on this box's non-ASCII repo path (the full suite runs
 * green), whereas a hand-rolled {@code main()} risks the Cyrillic-classpath {@code ClassNotFound}
 * fork bug. The {@link EnabledIfSystemProperty} guard means {@code clean test} skips this with zero
 * network and zero cost, yet it stays "one command" to run.
 *
 * <h2>Run it</h2>
 * <pre>
 *   # backend already up (Docker :8080 or native jar :8081), token = a valid Bearer JWT
 *   .\mvnw.cmd -o test -Dtest=EvalHarnessTest -Dmidas.eval.enabled=true \
 *       -Dmidas.eval.baseUrl=http://localhost:8080 -Dmidas.eval.token=eyJ...
 * </pre>
 *
 * <h2>Tunables ({@code -D})</h2>
 * <pre>
 *   midas.eval.baseUrl        (default http://localhost:8080)
 *   midas.eval.token          Bearer JWT (default empty → no auth header)
 *   midas.eval.goldenSet      filesystem path to a golden-set.json (default: classpath eval/golden-set.json)
 *   midas.eval.concurrency    parallel runs (default 2 — the pipeline is heavy)
 *   midas.eval.timeoutSec     per-run terminal-state budget (default 1200)
 *   midas.eval.pollSec        status poll interval (default 5)
 *   midas.eval.filterKind     comma list of kinds to include (default: all)
 *   midas.eval.filterId       comma list of idea ids to include (default: all) — cheap smoke subset
 *   midas.eval.minPassRate    optional CI gate: fail the command if passRate below this (default: none)
 *   midas.eval.outDir         report output dir (default target/eval-report)
 * </pre>
 */
@Tag("eval")
@EnabledIfSystemProperty(named = "midas.eval.enabled", matches = "true")
class EvalHarnessTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void runGoldenSet() throws Exception {
        EvalConfig cfg = EvalConfig.fromSystemProperties();
        System.out.printf("[eval] backend=%s golden=%s concurrency=%d timeout=%ds%n",
                cfg.baseUrl, cfg.goldenSet.isBlank() ? "classpath:eval/golden-set.json" : cfg.goldenSet,
                cfg.concurrency, cfg.timeoutSec);

        List<EvalIdea> ideas = filter(loadIdeas(cfg), cfg);
        if (ideas.isEmpty()) {
            throw new IllegalStateException("Golden-set produced 0 ideas after filtering — check filters/path.");
        }
        System.out.printf("[eval] running %d idea(s)%n", ideas.size());

        EvalHarnessClient client = new EvalHarnessClient(cfg.baseUrl, cfg.token, mapper);
        Set<String> observedModels = Collections.synchronizedSet(new LinkedHashSet<>());
        long startedAt = System.currentTimeMillis();

        List<EvalRunResult> results = executeAll(ideas, cfg, client, observedModels);

        EvalReport report = new EvalReport(results, cfg.baseUrl, String.join(", ", observedModels), startedAt);
        writeReports(cfg, report);
        System.out.println(report.toConsole());

        if (cfg.minPassRate != null) {
            assertTrue(report.passRate() >= cfg.minPassRate,
                    String.format("Eval gate: passRate %.3f < required %.3f", report.passRate(), cfg.minPassRate));
        }
    }

    // ── run orchestration ──────────────────────────────────────────────────

    private List<EvalRunResult> executeAll(List<EvalIdea> ideas, EvalConfig cfg,
                                           EvalHarnessClient client, Set<String> observedModels) {
        ExecutorService pool = Executors.newFixedThreadPool(cfg.concurrency);
        try {
            List<CompletableFuture<EvalRunResult>> futures = new ArrayList<>();
            for (EvalIdea idea : ideas) {
                futures.add(CompletableFuture.supplyAsync(() -> runOne(idea, cfg, client, observedModels), pool));
            }
            List<EvalRunResult> results = new ArrayList<>();
            for (CompletableFuture<EvalRunResult> f : futures) {
                results.add(f.join());
            }
            return results;
        } finally {
            pool.shutdown();
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private EvalRunResult runOne(EvalIdea idea, EvalConfig cfg,
                                 EvalHarnessClient client, Set<String> observedModels) {
        long startNs = System.nanoTime();
        String runId = null;
        try {
            runId = client.start(idea.prompt());
            long deadlineNs = startNs + cfg.timeoutSec * 1_000_000_000L;
            String state = null;
            boolean timedOut = false;
            while (true) {
                state = client.status(runId);
                if (EvalOutcome.isTerminal(state)) break;
                if (System.nanoTime() >= deadlineNs) {
                    timedOut = true;
                    break;
                }
                Thread.sleep(cfg.pollSec * 1000L);
            }
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            EvalOutcome outcome = EvalOutcome.classify(state, timedOut, false);
            System.out.printf("[eval] %-24s → %-10s (%s, %.0fs)%n", idea.id(), state, outcome, latencyMs / 1000.0);
            return observe(idea, runId, state, outcome, latencyMs, client, observedModels);
        } catch (Exception e) {
            long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            System.out.printf("[eval] %-24s → CLIENT_ERROR (%s)%n", idea.id(), e.getMessage());
            return new EvalRunResult(idea.id(), idea.kind(), idea.expected(), runId, null,
                    EvalOutcome.CLIENT_ERROR, "HARNESS", latencyMs, 0, 0, null, null, null, 0,
                    "harness error: " + e.getMessage());
        }
    }

    /** Assembles the per-run result from the read-only context + dashboard snapshots. */
    private EvalRunResult observe(EvalIdea idea, String runId, String terminalState, EvalOutcome outcome,
                                  long latencyMs, EvalHarnessClient client, Set<String> observedModels) {
        JsonNode ctx = null;
        try {
            ctx = client.context(runId);
        } catch (Exception ignored) {
            // context fetch failure is non-fatal — we still know the terminal state
        }
        JsonNode dash = client.dashboardRun(runId);

        String failureReason = outcome == EvalOutcome.PASS ? null : extractFailureReason(ctx);
        String stageReached = extractStageReached(ctx, outcome, terminalState);
        Double quality = extractDouble(ctx, "qualityScore", "overall");
        String verdict = extractText(ctx, "productReviewReport", "verdict");
        int remediation = ctx == null ? 0 : ctx.path("productReviewRemediationAttempts").asInt(0);

        int promptTokens = 0, completionTokens = 0;
        Double costUsd = null;
        if (dash != null) {
            promptTokens = dash.path("promptTokens").asInt(0);
            completionTokens = dash.path("completionTokens").asInt(0);
            if (dash.hasNonNull("estimatedCostUsd")) {
                costUsd = dash.path("estimatedCostUsd").asDouble();
            }
            collectModels(dash, observedModels);
        }

        return new EvalRunResult(idea.id(), idea.kind(), idea.expected(), runId, terminalState, outcome,
                stageReached, latencyMs, promptTokens, completionTokens, costUsd,
                quality, verdict, remediation, failureReason);
    }

    // ── defensive JSON extraction ────────────────────────────────────────────

    private static String extractFailureReason(JsonNode ctx) {
        if (ctx == null) return null;
        String last = ctx.path("lastErrorMessage").asText(null);
        if (last != null && !last.isBlank()) return last;
        // fall back to the last ERROR audit detail
        String detail = null;
        for (JsonNode e : ctx.path("auditLog")) {
            if ("ERROR".equals(e.path("severity").asText())) {
                String d = e.path("detail").asText(null);
                detail = (d != null && !d.isBlank()) ? d : e.path("event").asText(null);
            }
        }
        return detail;
    }

    private static String extractStageReached(JsonNode ctx, EvalOutcome outcome, String terminalState) {
        if (outcome == EvalOutcome.PASS) return "COMPLETED";
        if (ctx != null) {
            String errStage = null;
            for (JsonNode e : ctx.path("auditLog")) {
                if ("ERROR".equals(e.path("severity").asText())) {
                    errStage = e.path("stage").asText(null);
                }
            }
            if (errStage != null && !errStage.isBlank()) return errStage;
        }
        return terminalState == null ? "UNKNOWN" : terminalState;
    }

    private static Double extractDouble(JsonNode ctx, String parent, String field) {
        if (ctx == null) return null;
        JsonNode n = ctx.path(parent).path(field);
        return n.isNumber() ? n.asDouble() : null;
    }

    private static String extractText(JsonNode ctx, String parent, String field) {
        if (ctx == null) return null;
        JsonNode n = ctx.path(parent).path(field);
        return n.isTextual() ? n.asText() : null;
    }

    private static void collectModels(JsonNode dash, Set<String> observedModels) {
        for (JsonNode log : dash.path("agentLogs")) {
            String m = log.path("modelId").asText(null);
            if (m != null && !m.isBlank()) observedModels.add(m);
        }
    }

    // ── golden-set loading / filtering ─────────────────────────────────────

    private List<EvalIdea> loadIdeas(EvalConfig cfg) throws Exception {
        JsonNode root;
        if (!cfg.goldenSet.isBlank()) {
            byte[] bytes = Files.readAllBytes(Path.of(cfg.goldenSet));
            root = mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
        } else {
            try (InputStream in = getClass().getResourceAsStream("/eval/golden-set.json")) {
                if (in == null) throw new IllegalStateException("classpath:eval/golden-set.json not found");
                root = mapper.readTree(in);
            }
        }
        return EvalIdea.load(root);
    }

    private List<EvalIdea> filter(List<EvalIdea> ideas, EvalConfig cfg) {
        Set<String> kinds = csv(cfg.filterKind);
        Set<String> ids = csv(cfg.filterId);
        List<EvalIdea> out = new ArrayList<>();
        for (EvalIdea idea : ideas) {
            if (!kinds.isEmpty() && !kinds.contains(idea.kind().toUpperCase())) continue;
            if (!ids.isEmpty() && !ids.contains(idea.id())) continue;
            out.add(idea);
        }
        return out;
    }

    private static Set<String> csv(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null || s.isBlank()) return out;
        for (String part : s.split(",")) {
            String p = part.strip();
            if (!p.isBlank()) out.add(p);
        }
        return out;
    }

    // ── report output ────────────────────────────────────────────────────────

    private void writeReports(EvalConfig cfg, EvalReport report) throws Exception {
        Path dir = Path.of(cfg.outDir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("report.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report.toJsonMap()));
        Files.writeString(dir.resolve("report.md"), report.toMarkdown());
        System.out.printf("[eval] report written to %s (report.json, report.md)%n", dir.toAbsolutePath());
    }

    // ── config ─────────────────────────────────────────────────────────────

    private record EvalConfig(
            String baseUrl, String token, String goldenSet, int concurrency, int timeoutSec,
            int pollSec, String filterKind, String filterId, Double minPassRate, String outDir) {

        static EvalConfig fromSystemProperties() {
            return new EvalConfig(
                    prop("midas.eval.baseUrl", "http://localhost:8080"),
                    prop("midas.eval.token", ""),
                    prop("midas.eval.goldenSet", ""),
                    intProp("midas.eval.concurrency", 2),
                    intProp("midas.eval.timeoutSec", 1200),
                    intProp("midas.eval.pollSec", 5),
                    prop("midas.eval.filterKind", ""),
                    prop("midas.eval.filterId", ""),
                    doubleProp("midas.eval.minPassRate"),
                    prop("midas.eval.outDir", "target/eval-report"));
        }

        private static String prop(String key, String def) {
            String v = System.getProperty(key);
            return v == null || v.isBlank() ? def : v.strip();
        }

        private static int intProp(String key, int def) {
            String v = System.getProperty(key);
            try {
                return v == null || v.isBlank() ? def : Integer.parseInt(v.strip());
            } catch (NumberFormatException e) {
                return def;
            }
        }

        private static Double doubleProp(String key) {
            String v = System.getProperty(key);
            try {
                return v == null || v.isBlank() ? null : Double.parseDouble(v.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
