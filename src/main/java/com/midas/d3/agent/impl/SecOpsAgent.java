package com.midas.d3.agent.impl;

import com.midas.d3.agent.AgentSystemPrompts;
import com.midas.d3.agent.base.BaseMidasAgent;
import com.midas.d3.context.ContextReducer;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.statemachine.ValidatorRegistry;
import org.springframework.stereotype.Component;

/**
 * Agent 6 — SecOps / DevSecOps Engineer (the Reality Checker).
 *
 * <p>Audits against the actual attack surface (e.g. manifest.json permissions for extensions)
 * and prepares release artifacts appropriate to the deployment model (containers only when needed).
 * Requires upstream artifacts: {@code technicalSpec}, {@code architectureDesign},
 * {@code generatedSourceCode}, {@code generatedTests}.
 */
@Component
public class SecOpsAgent extends BaseMidasAgent {

    public SecOpsAgent(LlmClient         llmClient,
                       ContextReducer    contextReducer,
                       ValidatorRegistry validatorRegistry) {
        super(llmClient, contextReducer, validatorRegistry);
    }

    @Override
    public ContextReducer.AgentRole getRole() {
        return ContextReducer.AgentRole.SECOPS_ENGINEER;
    }

    @Override
    public String getAgentName() {
        return "SecOpsAgent";
    }

    @Override
    public String getSystemPrompt() {
        return AgentSystemPrompts.SECOPS_ENGINEER_PROMPT;
    }
}
