package com.midas.d3.evolution;

import com.midas.d3.llm.LlmCallException;
import com.midas.d3.llm.LlmCallRequest;
import com.midas.d3.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Background code-review service that operates independently of the main pipeline
 * State Machine.
 *
 * <h2>Role</h2>
 * Acts as a <em>Senior Staff Engineer</em> reviewing pipeline-generated project code
 * extracted from completed pipeline runs.  Its analysis covers:
 * <ul>
 *   <li>Architectural flaws (SOLID/DRY/KISS violations, coupling, missing abstraction)</li>
 *   <li>Algorithm complexity — identifying O(n²) where O(n log n) or better is achievable</li>
 *   <li>Memory issues — resource leaks, missing try-with-resources, excessive allocations</li>
 *   <li>Security vulnerabilities — SQL injection, XSS, missing validation, hardcoded secrets</li>
 *   <li>Concurrency problems — race conditions, improper thread pool usage, deadlock risks</li>
 *   <li>Code quality — magic values, deep nesting, poor naming, missing null-guards</li>
 * </ul>
 *
 * <h2>Output</h2>
 * Returns a structured Markdown report with concrete code snippets showing both
 * the problematic pattern and its corrected form.
 *
 * <h2>Independence from State Machine</h2>
 * {@code EvolutionAgent} is driven by {@link ContinuousImprovementService} on a
 * background schedule and does <em>not</em> participate in any pipeline state
 * transition.  It uses {@link LlmClient} directly, bypassing
 * {@code BaseMidasAgent}'s validation loop — the evolution report is free-form
 * Markdown, not a JSON artifact requiring schema validation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvolutionAgent {

    static final String AGENT_NAME = "EvolutionAgent";

    /**
     * Maximum characters of code context to pass to the LLM.
     * Keeps the user-message within a manageable token window (~6 000 tokens at 4 ch/token).
     */
    static final int MAX_CODE_CONTEXT_CHARS = 24_000;

    private static final String SYSTEM_PROMPT = """
            You are a Senior Staff Engineer with 15+ years of production experience in
            Java, Spring Boot, microservices, and distributed systems. You are performing
            a structured code review of a software project produced end-to-end
            by the MIDAS D3 pipeline.

            Your goal is to identify every opportunity for improvement and explain it
            clearly so a developer can act on it immediately.

            Analysis dimensions:

            1. ARCHITECTURE & DESIGN
               - SOLID violations (God classes, anemic domain, inappropriate coupling)
               - Missing or incorrect abstraction layers
               - Misuse of design patterns

            2. ALGORITHM COMPLEXITY & DATA STRUCTURES
               - O(n²) or worse where a better algorithm exists
               - N+1 query problems (missing JOIN FETCH / batch loading)
               - Wrong collection type for the access pattern

            3. MEMORY & RESOURCE MANAGEMENT
               - Unclosed streams, connections, or file handles (missing try-with-resources)
               - Excessive object creation in hot loops
               - Potential memory leaks (event listener registration without deregistration)

            4. SECURITY
               - SQL injection via string concatenation instead of parameterized queries
               - Missing input validation / output encoding (XSS)
               - Hardcoded credentials or insecure default configurations
               - Missing authorization checks on endpoints

            5. CONCURRENCY
               - Shared mutable state accessed without synchronization
               - Incorrect use of @Async / thread pool
               - Potential deadlocks or livelock scenarios

            6. CODE QUALITY & MAINTAINABILITY
               - Magic numbers / strings that should be named constants
               - Deep nesting that reduces readability
               - Misleading or absent Javadoc on public API
               - Inconsistent error handling strategy

            Output format — respond ONLY with the following Markdown structure:

            # MIDAS Evolution Report

            ## Executive Summary
            [2–3 sentences: overall quality assessment and top-3 risk areas]

            ## 🔴 Critical Issues (P0 — fix before production)
            For each issue: problem description, code snippet showing the defect,
            corrected code snippet, and explanation of why the fix matters.

            ## 🟠 High Priority (P1 — fix in next sprint)
            Same format as P0.

            ## 🟡 Optimization Opportunities (P2 — improve when time allows)
            Same format as above; focus on performance and readability improvements.

            ## ✅ Positive Highlights
            What was done well — patterns worth preserving or replicating.

            Be specific: always reference the class name and method/field involved.
            Provide compilable Java code snippets for every suggested fix.
            If no issues are found in a severity category, write "None identified."
            """;

    private final LlmClient llmClient;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Performs a comprehensive code review of the supplied code context and
     * returns a structured Markdown report.
     *
     * @param codeContext  concatenated agent outputs for a pipeline run (truncated
     *                     to {@value #MAX_CODE_CONTEXT_CHARS} chars before the call)
     * @param pipelineRunId run ID used for distributed tracing in LLM client logs
     * @return Markdown-formatted evolution report
     * @throws LlmCallException if the LLM is unreachable or returns an empty response
     */
    public String analyzeCode(String codeContext, String pipelineRunId) {
        String truncated = codeContext.length() > MAX_CODE_CONTEXT_CHARS
                ? codeContext.substring(0, MAX_CODE_CONTEXT_CHARS) + "\n\n[...truncated for token budget]"
                : codeContext;

        LlmCallRequest request = LlmCallRequest.builder()
                .stage(null)
                .agentName(AGENT_NAME)
                .systemPrompt(SYSTEM_PROMPT)
                .userMessage(truncated)
                .pipelineRunId(pipelineRunId)
                .modelOverride(llmClient.defaultModelId())
                .build();

        log.info("[EvolutionAgent] Starting analysis for run [{}] ({} chars of context).",
                pipelineRunId, truncated.length());

        try {
            String report = llmClient.call(request).text();
            log.info("[EvolutionAgent] Analysis complete for run [{}] ({} chars in report).",
                    pipelineRunId, report.length());
            return report;
        } catch (LlmCallException e) {
            log.error("[EvolutionAgent] LLM call failed for run [{}]: {}",
                    pipelineRunId, e.getMessage(), e);
            throw e;
        }
    }
}
