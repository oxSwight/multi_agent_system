package com.midas.d3.agent.impl;

import com.midas.d3.agent.AgentSystemPrompts;
import com.midas.d3.agent.base.BaseMidasAgent;
import com.midas.d3.context.ContextReducer;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.statemachine.ValidatorRegistry;
import org.springframework.stereotype.Component;

/**
 * Agent 3 — Integration Engineer.
 *
 * <p>Designs integrations only for external services the system genuinely depends on,
 * respecting the client-side/CORS constraints of the chosen runtime.
 * Requires upstream artifacts: {@code technicalSpec}, {@code architectureDesign}.
 */
@Component
public class IntegrationEngineerAgent extends BaseMidasAgent {

    public IntegrationEngineerAgent(LlmClient         llmClient,
                                    ContextReducer    contextReducer,
                                    ValidatorRegistry validatorRegistry) {
        super(llmClient, contextReducer, validatorRegistry);
    }

    @Override
    public ContextReducer.AgentRole getRole() {
        return ContextReducer.AgentRole.INTEGRATION_ENGINEER;
    }

    @Override
    public String getAgentName() {
        return "IntegrationEngineerAgent";
    }

    @Override
    public String getSystemPrompt() {
        return AgentSystemPrompts.INTEGRATION_ENGINEER_PROMPT;
    }
}
