package com.midas.d3.agent;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Applies {@link AgentRetryPolicyProperties} to the static {@link AgentRetryPolicy} limits at startup. */
@Component
@RequiredArgsConstructor
public class AgentRetryPolicyBootstrap {

    private final AgentRetryPolicyProperties properties;

    @PostConstruct
    void apply() {
        AgentRetryPolicy.configure(properties.getParseMaxAttempts(), properties.getValidationMaxAttempts());
    }
}
