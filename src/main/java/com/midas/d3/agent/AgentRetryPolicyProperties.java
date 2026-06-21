package com.midas.d3.agent;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configurable retry limits for in-agent LLM loops (parse vs schema/business validation).
 *
 * <p>Distinct from {@code midas.validation.max-retries}, which governs state-machine stage retries.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "midas.agent-retry")
public class AgentRetryPolicyProperties {

    /**
     * Total attempts when the only failure is a JSON parse error (1 retry = 2 attempts).
     */
    private int parseMaxAttempts = 2;

    /**
     * Total attempts for schema/business validation failures.
     */
    private int validationMaxAttempts = 3;
}
