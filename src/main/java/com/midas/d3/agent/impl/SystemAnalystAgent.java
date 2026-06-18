package com.midas.d3.agent.impl;

import com.midas.d3.agent.AgentSystemPrompts;
import com.midas.d3.agent.base.BaseMidasAgent;
import com.midas.d3.context.ContextReducer;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.llm.LlmModelPolicy;
import com.midas.d3.statemachine.ValidatorRegistry;
import org.springframework.stereotype.Component;

/**
 * Agent 1 — System Analyst (the Boundary Setter).
 *
 * <p>Transforms the raw user idea into a strict Technical Specification and locks the
 * binding {@code runtime_environment} boundary for all downstream agents.
 * Requires no upstream artifacts (first agent in the chain).
 */
@Component
public class SystemAnalystAgent extends BaseMidasAgent {

    public SystemAnalystAgent(LlmClient         llmClient,
                              ContextReducer    contextReducer,
                              ValidatorRegistry validatorRegistry,
                              LlmModelPolicy    llmModelPolicy) {
        super(llmClient, contextReducer, validatorRegistry, llmModelPolicy);
    }

    @Override
    public ContextReducer.AgentRole getRole() {
        return ContextReducer.AgentRole.SYSTEM_ANALYST;
    }

    @Override
    public String getAgentName() {
        return "SystemAnalystAgent";
    }

    @Override
    public String getSystemPrompt() {
        return AgentSystemPrompts.SYSTEM_ANALYST_PROMPT;
    }
}
