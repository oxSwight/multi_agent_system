package com.midas.d3.agent.impl;

import com.midas.d3.agent.AgentSystemPrompts;
import com.midas.d3.agent.base.AgentResult;
import com.midas.d3.agent.base.BaseMidasAgent;
import com.midas.d3.agent.implementation.CodeGenerationCoordinator;
import com.midas.d3.context.ContextReducer;
import com.midas.d3.context.MidasContext;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.statemachine.ValidatorRegistry;
import org.springframework.stereotype.Component;

/**
 * Agent 4 — Lead Implementation Engineer (role enum {@code IMPLEMENTATION_ENGINEER}).
 *
 * <p>Stack-agnostic by design: generates 100% complete, runnable code in the language and
 * platform dictated by the architecture (JS/TS + manifest.json for a Manifest V3 extension,
 * Java 21 for a Spring backend, etc.) — it never imposes a backend stack on a client-side
 * product. Enforces the Zero-Placeholder policy.
 *
 * <p>Requires upstream artifacts: {@code technicalSpec}, {@code architectureDesign}, and
 * (skip-aware) {@code integrationStrategy} — the latter is omitted gracefully when the
 * Integration stage is routed around for self-contained products.
 */
@Component
public class ImplementationEngineerAgent extends BaseMidasAgent {

    private final CodeGenerationCoordinator codeGenerationCoordinator;

    public ImplementationEngineerAgent(LlmClient                  llmClient,
                                       ContextReducer             contextReducer,
                                       ValidatorRegistry          validatorRegistry,
                                       CodeGenerationCoordinator  codeGenerationCoordinator) {
        super(llmClient, contextReducer, validatorRegistry);
        this.codeGenerationCoordinator = codeGenerationCoordinator;
    }

    @Override
    protected AgentResult tryCustomExecution(MidasContext context) {
        return codeGenerationCoordinator.execute(context, getAgentName());
    }

    @Override
    public ContextReducer.AgentRole getRole() {
        return ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER;
    }

    @Override
    public String getAgentName() {
        return "ImplementationEngineerAgent";
    }

    @Override
    public String getSystemPrompt() {
        return AgentSystemPrompts.IMPLEMENTATION_ENGINEER_PROMPT;
    }
}
