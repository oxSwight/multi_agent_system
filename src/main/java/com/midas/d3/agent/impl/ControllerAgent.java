package com.midas.d3.agent.impl;

import com.midas.d3.agent.AgentSystemPrompts;
import com.midas.d3.agent.base.BaseMidasAgent;
import com.midas.d3.context.ContextReducer;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.llm.LlmModelPolicy;
import com.midas.d3.statemachine.ValidatorRegistry;
import org.springframework.stereotype.Component;

/**
 * Agent 7 — Controller / Product Owner (role enum {@code CONTROLLER}).
 *
 * <p>The pipeline's final, BLOCKING quality gate. It does not re-check structural correctness
 * (the per-stage validators already do that); instead it judges <b>intent conformance</b> —
 * whether the delivered solution actually satisfies the original {@code rawUserIdea} and the
 * locked {@code business_goal}.
 *
 * <p>Runs at the {@link com.midas.d3.statemachine.MidasState#PRODUCT_REVIEW} stage. To stay well
 * under token limits it is fed only {@code technicalSpec} and {@code secOpsArtifacts} (which carry
 * the {@code release_artifacts} map of what was actually shipped) — never the full raw source code
 * (see {@link ContextReducer.AgentRole#CONTROLLER}).
 *
 * <p>Emits a {@code verdict} (PASS / PASS_WITH_NOTES / REJECT) plus a {@code coverage_matrix} and a
 * {@code remediation_block}. A passing verdict advances the pipeline to COMPLETED; a REJECT routes
 * to ERROR with the report attached.
 */
@Component
public class ControllerAgent extends BaseMidasAgent {

    public ControllerAgent(LlmClient         llmClient,
                           ContextReducer    contextReducer,
                           ValidatorRegistry validatorRegistry,
                           LlmModelPolicy    llmModelPolicy) {
        super(llmClient, contextReducer, validatorRegistry, llmModelPolicy);
    }

    @Override
    public ContextReducer.AgentRole getRole() {
        return ContextReducer.AgentRole.CONTROLLER;
    }

    @Override
    public String getAgentName() {
        return "ControllerAgent";
    }

    @Override
    public String getSystemPrompt() {
        return AgentSystemPrompts.CONTROLLER_PROMPT;
    }
}
