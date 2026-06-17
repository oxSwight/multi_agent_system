package com.midas.d3.agent.impl;

import com.midas.d3.agent.AgentSystemPrompts;
import com.midas.d3.agent.base.BaseMidasAgent;
import com.midas.d3.context.ContextReducer;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.statemachine.ValidatorRegistry;
import org.springframework.stereotype.Component;

/**
 * Agent 5 — QA Automation Engineer (the Reality Checker).
 *
 * <p>Writes tests using the framework that matches the actual runtime/tech stack
 * (Jest+jsdom for extensions/DOM, JUnit 5+Mockito for Java), covering core features and edge cases.
 * Requires upstream artifacts: {@code technicalSpec}, {@code architectureDesign}, {@code generatedSourceCode}.
 */
@Component
public class QaAutomationAgent extends BaseMidasAgent {

    public QaAutomationAgent(LlmClient         llmClient,
                             ContextReducer    contextReducer,
                             ValidatorRegistry validatorRegistry) {
        super(llmClient, contextReducer, validatorRegistry);
    }

    @Override
    public ContextReducer.AgentRole getRole() {
        return ContextReducer.AgentRole.QA_ENGINEER;
    }

    @Override
    public String getAgentName() {
        return "QaAutomationAgent";
    }

    @Override
    public String getSystemPrompt() {
        return AgentSystemPrompts.QA_ENGINEER_PROMPT;
    }
}
