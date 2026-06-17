package com.midas.d3.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Provides a dedicated {@link Executor} for long-running MIDAS agent tasks.
 *
 * <p>Each of the 7 pipeline agents may run for 30–120 seconds waiting for an LLM
 * response. They must NOT block Spring's shared executor or the SSM reactor thread.
 * This pool is sized to allow all 7 agents to run concurrently if multiple pipelines
 * are active simultaneously.
 */
@Slf4j
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /** Bean name referenced by {@link com.midas.d3.statemachine.action.AgentEntryAction}. */
    public static final String AGENT_EXECUTOR = "agentTaskExecutor";

    @Bean(name = AGENT_EXECUTOR)
    public Executor agentTaskExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(7);
        exec.setMaxPoolSize(24);
        exec.setQueueCapacity(100);
        exec.setThreadNamePrefix("midas-agent-");
        exec.setKeepAliveSeconds(60);
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(120);
        exec.initialize();
        log.info("[AsyncConfig] Agent executor pool initialized (core=7, max=24).");
        return exec;
    }
}
