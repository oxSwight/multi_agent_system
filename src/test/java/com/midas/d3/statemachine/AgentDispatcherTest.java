package com.midas.d3.statemachine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.agent.base.AgentResult;
import com.midas.d3.agent.base.BaseMidasAgent;
import com.midas.d3.context.MidasContext;
import com.midas.d3.persistence.PersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.support.DefaultExtendedState;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentDispatcher token persistence")
class AgentDispatcherTest {

    @Mock private BaseMidasAgent agent;
    @Mock private PersistenceService persistenceService;
    @Mock private StateMachine<MidasState, MidasEvent> machine;

    private AgentDispatcher dispatcher;
    private MidasContext context;

    @BeforeEach
    void setUp() {
        Executor syncExecutor = Runnable::run;
        when(agent.resolveStage()).thenReturn(MidasState.SECOPS_AUDIT);
        when(agent.getAgentName()).thenReturn("SecOpsAgent");

        dispatcher = new AgentDispatcher(List.of(agent), syncExecutor, persistenceService);

        context = MidasContext.start("Build API", "run-dispatch-001");
        Map<Object, Object> variables = new HashMap<>();
        variables.put(PipelineContextKeys.MIDAS_CONTEXT, context);
        variables.put(PipelineContextKeys.AUTO_MODE_KEY, true);

        DefaultExtendedState extendedState = new DefaultExtendedState(variables);
        when(machine.getExtendedState()).thenReturn(extendedState);
    }

    @Test
    @DisplayName("Persists non-zero token counts and modelId from AgentResult on success")
    void dispatch_success_persistsTokenUsageAndModelId() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentResult result = new AgentResult(
                mapper.createObjectNode(),
                "{\"audit\":\"passed\"}",
                1,
                1500,
                420,
                "gemini-1.5-flash");
        when(agent.execute(context)).thenReturn(result);
        when(machine.sendEvent(any(Mono.class))).thenReturn(Flux.empty());

        dispatcher.dispatch(machine, MidasState.SECOPS_AUDIT);

        ArgumentCaptor<String> modelIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(persistenceService).logAgentExecution(
                eq("run-dispatch-001"),
                eq("SecOpsAgent"),
                eq("{\"audit\":\"passed\"}"),
                modelIdCaptor.capture(),
                eq(1500),
                eq(420),
                anyLong(),
                eq(false),
                eq(""));
        assertThat(modelIdCaptor.getValue()).isEqualTo("gemini-1.5-flash");
        verify(machine).sendEvent(any(Mono.class));
    }
}
