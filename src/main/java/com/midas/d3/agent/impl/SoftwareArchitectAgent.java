package com.midas.d3.agent.impl;

import com.midas.d3.agent.AgentSystemPrompts;
import com.midas.d3.agent.base.BaseMidasAgent;
import com.midas.d3.context.ContextReducer;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.statemachine.ValidatorRegistry;
import org.springframework.stereotype.Component;

/**
 * Agent 2 — Software Architect (the Tech Stack Selector).
 *
 * <p>Selects the smallest architecture that satisfies the spec, obeying the
 * {@code runtime_environment} boundary (client-first; backend/DB/REST only when required).
 * Requires upstream artifact: {@code technicalSpec}.
 */
@Component
public class SoftwareArchitectAgent extends BaseMidasAgent {

    public SoftwareArchitectAgent(LlmClient         llmClient,
                                  ContextReducer    contextReducer,
                                  ValidatorRegistry validatorRegistry) {
        super(llmClient, contextReducer, validatorRegistry);
    }

    @Override
    public ContextReducer.AgentRole getRole() {
        return ContextReducer.AgentRole.SOFTWARE_ARCHITECT;
    }

    @Override
    public String getAgentName() {
        return "SoftwareArchitectAgent";
    }

    @Override
    public String getSystemPrompt() {
        return AgentSystemPrompts.SOFTWARE_ARCHITECT_PROMPT;
    }
}
