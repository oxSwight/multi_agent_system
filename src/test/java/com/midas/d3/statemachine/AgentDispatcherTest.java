package com.midas.d3.statemachine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.agent.base.AgentResult;
import com.midas.d3.agent.base.BaseMidasAgent;
import com.midas.d3.agent.implementation.CodeGapDegradationException;
import com.midas.d3.build.BuildVerificationService;
import com.midas.d3.context.ContextReducer;
import com.midas.d3.context.MidasContext;
import com.midas.d3.persistence.PersistenceService;
import com.midas.d3.telegram.TelegramPipelineBot;
import org.springframework.messaging.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.state.State;
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
    @Mock private BuildVerificationService buildVerificationService;
    @Mock private StateMachine<MidasState, MidasEvent> machine;
    @Mock private ObjectProvider<PipelineOrchestrator> orchestratorProvider;
    @Mock private PipelineOrchestrator orchestrator;
    @Mock private ObjectProvider<TelegramPipelineBot> telegramBotProvider;
    @Mock private TelegramPipelineBot telegramBot;

    private AgentDispatcher dispatcher;
    private MidasContext context;
    private DefaultExtendedState extendedState;

    @BeforeEach
    void setUp() {
        Executor syncExecutor = Runnable::run;
        when(agent.resolveStage()).thenReturn(MidasState.SECOPS_AUDIT);
        when(agent.getAgentName()).thenReturn("SecOpsAgent");

        dispatcher = new AgentDispatcher(
                List.of(agent), syncExecutor, persistenceService, buildVerificationService,
                orchestratorProvider, telegramBotProvider, true, 0L);

        context = MidasContext.start("Build API", "run-dispatch-001");
        Map<Object, Object> variables = new HashMap<>();
        variables.put(PipelineContextKeys.MIDAS_CONTEXT, context);
        variables.put(PipelineContextKeys.AUTO_MODE_KEY, true);

        extendedState = new DefaultExtendedState(variables);
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

    @Test
    @DisplayName("Auto-mode BUILD_VERIFICATION runs the build and submits its report (no LLM agent, no stall)")
    @SuppressWarnings("unchecked")
    void dispatch_buildVerification_runsBuildAndSubmitsReport() {
        String reportJson = "{\"build_status\":\"SUCCESS\",\"tool\":\"MAVEN\"}";
        when(buildVerificationService.verifyToReportJson(context)).thenReturn(reportJson);
        when(machine.sendEvent(any(Mono.class))).thenReturn(Flux.empty());

        // No BaseMidasAgent is registered for BUILD_VERIFICATION — the dispatcher must drive it itself.
        dispatcher.dispatchIfAutoMode(machine, MidasState.BUILD_VERIFICATION);

        verify(buildVerificationService).verifyToReportJson(context);
        verify(persistenceService).updateRunStatus("run-dispatch-001", "BUILD_VERIFICATION");
        verify(persistenceService).logAgentExecution(
                eq("run-dispatch-001"), eq("BuildVerification"), eq(reportJson),
                isNull(), eq(0), eq(0), anyLong(), eq(false));

        // The report is fed back as SUBMIT_RESULT so BUILD_CHOICE can route — this is the un-wedge.
        ArgumentCaptor<Mono<Message<MidasEvent>>> eventCaptor = ArgumentCaptor.forClass(Mono.class);
        verify(machine).sendEvent(eventCaptor.capture());
        Message<MidasEvent> submitted = eventCaptor.getValue().block();
        assertThat(submitted).isNotNull();
        assertThat(submitted.getPayload()).isEqualTo(MidasEvent.SUBMIT_RESULT);
        assertThat(submitted.getHeaders().get(PipelineContextKeys.LLM_OUTPUT_HEADER))
                .isEqualTo(reportJson);
    }

    // ── Graceful degradation (COMPLETED_WITH_GAPS) ────────────────────────────

    @Test
    @DisplayName("Unhealable functional gap + salvageable partial + degradation enabled → DEGRADE_ARTIFACT")
    @SuppressWarnings("unchecked")
    void dispatch_codeGap_degradationEnabled_emitsDegradeArtifact() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode partial  = mapper.readTree("{\"src/main/java/A.java\":\"class A {}\"}");
        JsonNode manifest = mapper.readTree("[{\"feature_name\":\"Create task\"}]");
        when(agent.execute(context)).thenThrow(new CodeGapDegradationException(
                "ImplementationAgent", ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER,
                3, "assembled envelope rejected", partial, manifest, List.of("Assign not implemented"),
                1200, 340, "qwen2.5-coder:14b"));
        when(machine.sendEvent(any(Mono.class))).thenReturn(Flux.empty());

        dispatcher.dispatch(machine, MidasState.SECOPS_AUDIT);

        // The partial payload is handed to DegradeToGapsAction via ExtendedState, not message headers.
        Map<Object, Object> vars = machine.getExtendedState().getVariables();
        assertThat(vars.get(PipelineContextKeys.DEGRADATION_PARTIAL_SOURCE)).isEqualTo(partial);
        assertThat(vars.get(PipelineContextKeys.DEGRADATION_FEATURE_MANIFEST)).isEqualTo(manifest);
        assertThat(vars.get(PipelineContextKeys.DEGRADATION_GAPS))
                .isEqualTo(List.of("Assign not implemented"));

        // A delivered, degraded product — logged as NON-error (isError=false) with the REAL tokens+model
        // already consumed, so this sold path records true cost instead of $0.
        verify(persistenceService).logAgentExecution(
                eq("run-dispatch-001"), eq("SecOpsAgent"), contains("[DEGRADED]"),
                eq("qwen2.5-coder:14b"), eq(1200), eq(340), anyLong(), eq(false));

        ArgumentCaptor<Mono<Message<MidasEvent>>> eventCaptor = ArgumentCaptor.forClass(Mono.class);
        verify(machine).sendEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().block().getPayload()).isEqualTo(MidasEvent.DEGRADE_ARTIFACT);
    }

    @Test
    @DisplayName("Unhealable gap, degradation enabled but NO salvageable partial → CRITICAL_FAILURE")
    @SuppressWarnings("unchecked")
    void dispatch_codeGap_enabledButNotSalvageable_emitsCriticalFailure() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // Empty object partial → hasSalvageablePartial() == false even though degradation is enabled
        // (setUp dispatcher has degradationEnabled=true). Exercises the salvageability half of the guard.
        when(agent.execute(context)).thenThrow(new CodeGapDegradationException(
                "ImplementationAgent", ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER,
                3, "nothing salvageable", mapper.createObjectNode(), mapper.createArrayNode(), List.of("gap"),
                0, 0, "qwen2.5-coder:14b"));
        when(machine.sendEvent(any(Mono.class))).thenReturn(Flux.empty());
        // Machine accepts CRITICAL_FAILURE and reaches ERROR — no durable-ERROR fallback needed.
        State<MidasState, MidasEvent> errorState = stateOf(MidasState.ERROR);
        when(machine.getState()).thenReturn(errorState);

        dispatcher.dispatch(machine, MidasState.SECOPS_AUDIT);

        // Nothing to deliver → no degradation payload published; the run fails like any exhausted agent.
        Map<Object, Object> vars = machine.getExtendedState().getVariables();
        assertThat(vars).doesNotContainKey(PipelineContextKeys.DEGRADATION_PARTIAL_SOURCE);
        verify(persistenceService).logAgentExecution(
                eq("run-dispatch-001"), eq("SecOpsAgent"), anyString(),
                isNull(), eq(0), eq(0), anyLong(), eq(true));

        ArgumentCaptor<Mono<Message<MidasEvent>>> eventCaptor = ArgumentCaptor.forClass(Mono.class);
        verify(machine).sendEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().block().getPayload()).isEqualTo(MidasEvent.CRITICAL_FAILURE);
    }

    @Test
    @DisplayName("Unhealable functional gap but degradation disabled → CRITICAL_FAILURE (fallback)")
    @SuppressWarnings("unchecked")
    void dispatch_codeGap_degradationDisabled_emitsCriticalFailure() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode partial = mapper.readTree("{\"src/main/java/A.java\":\"class A {}\"}");
        AgentDispatcher disabled = new AgentDispatcher(
                List.of(agent), Runnable::run, persistenceService, buildVerificationService,
                orchestratorProvider, telegramBotProvider, false, 0L);
        when(agent.execute(context)).thenThrow(new CodeGapDegradationException(
                "ImplementationAgent", ContextReducer.AgentRole.IMPLEMENTATION_ENGINEER,
                3, "assembled envelope rejected", partial, mapper.createArrayNode(), List.of("gap"),
                0, 0, "qwen2.5-coder:14b"));
        when(machine.sendEvent(any(Mono.class))).thenReturn(Flux.empty());
        // Machine accepts CRITICAL_FAILURE and reaches ERROR — no durable-ERROR fallback needed.
        State<MidasState, MidasEvent> errorState = stateOf(MidasState.ERROR);
        when(machine.getState()).thenReturn(errorState);

        disabled.dispatch(machine, MidasState.SECOPS_AUDIT);

        // No degradation payload published; the run fails like any other exhausted agent.
        Map<Object, Object> vars = machine.getExtendedState().getVariables();
        assertThat(vars).doesNotContainKey(PipelineContextKeys.DEGRADATION_PARTIAL_SOURCE);
        verify(persistenceService).logAgentExecution(
                eq("run-dispatch-001"), eq("SecOpsAgent"), anyString(),
                isNull(), eq(0), eq(0), anyLong(), eq(true));

        ArgumentCaptor<Mono<Message<MidasEvent>>> eventCaptor = ArgumentCaptor.forClass(Mono.class);
        verify(machine).sendEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().block().getPayload()).isEqualTo(MidasEvent.CRITICAL_FAILURE);
    }

    // ── Durable-ERROR fallback (P3-4) ─────────────────────────────────────────

    @Test
    @DisplayName("CRITICAL_FAILURE not accepted (machine stuck non-terminal) → durable ERROR fallback: mark ERROR + evict")
    @SuppressWarnings("unchecked")
    void dispatch_criticalFailureNotAccepted_appliesDurableErrorFallback() throws Exception {
        when(agent.execute(context)).thenThrow(new RuntimeException("boom"));
        when(machine.sendEvent(any(Mono.class))).thenReturn(Flux.empty());
        // The machine did NOT transition to a terminal state on CRITICAL_FAILURE (denied event / wrong
        // state / already stopped) — it is still sitting in the processing stage.
        State<MidasState, MidasEvent> stuckState = stateOf(MidasState.SECOPS_AUDIT);
        when(machine.getState()).thenReturn(stuckState);
        when(orchestratorProvider.getObject()).thenReturn(orchestrator);
        // Machine is still registered (never evicted, since it never reached a terminal state) → wedge.
        when(orchestrator.hasActiveMachine("run-dispatch-001")).thenReturn(true);

        dispatcher.dispatch(machine, MidasState.SECOPS_AUDIT);

        // The CRITICAL_FAILURE event was still emitted…
        ArgumentCaptor<Mono<Message<MidasEvent>>> eventCaptor = ArgumentCaptor.forClass(Mono.class);
        verify(machine).sendEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().block().getPayload()).isEqualTo(MidasEvent.CRITICAL_FAILURE);
        // …and because the machine never reached a terminal state, the run is force-failed + evicted
        // instead of wedging forever.
        verify(persistenceService).failRun(eq("run-dispatch-001"), contains("boom"));
        verify(orchestrator).evictRun("run-dispatch-001");
    }

    @Test
    @DisplayName("CRITICAL_FAILURE accepted (machine reaches ERROR) → no durable-ERROR fallback")
    @SuppressWarnings("unchecked")
    void dispatch_criticalFailureAccepted_noFallback() throws Exception {
        when(agent.execute(context)).thenThrow(new RuntimeException("boom"));
        when(machine.sendEvent(any(Mono.class))).thenReturn(Flux.empty());
        State<MidasState, MidasEvent> errorState = stateOf(MidasState.ERROR);
        when(machine.getState()).thenReturn(errorState);

        dispatcher.dispatch(machine, MidasState.SECOPS_AUDIT);

        // sendCriticalFailure still ran and emitted CRITICAL_FAILURE — but because the machine reached a
        // terminal state it took the no-fallback branch: no force-fail, no eviction. (Guards against a
        // regression that drops the terminal check and always falls back.)
        ArgumentCaptor<Mono<Message<MidasEvent>>> eventCaptor = ArgumentCaptor.forClass(Mono.class);
        verify(machine).sendEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().block().getPayload()).isEqualTo(MidasEvent.CRITICAL_FAILURE);
        verify(persistenceService, never()).failRun(anyString(), anyString());
        verifyNoInteractions(orchestratorProvider);
    }

    @Test
    @DisplayName("Durable-ERROR wedge on a Telegram run → honest failure pushed to the chat")
    @SuppressWarnings("unchecked")
    void dispatch_wedge_telegramRun_pushesErrorToChat() throws Exception {
        // A Telegram-initiated run carries a chat + message id; the wedge leaves the machine outside
        // ERROR, so the state listener never renders the failure and the in-progress message is stale.
        MidasContext telegramCtx = context.withTelegramChatId(555L).withTelegramMessageId(42);
        extendedState.getVariables().put(PipelineContextKeys.MIDAS_CONTEXT, telegramCtx);

        when(agent.execute(telegramCtx)).thenThrow(new RuntimeException("boom"));
        when(machine.sendEvent(any(Mono.class))).thenReturn(Flux.empty());
        // Machine did NOT reach a terminal state on CRITICAL_FAILURE — genuine wedge.
        State<MidasState, MidasEvent> stuckState = stateOf(MidasState.SECOPS_AUDIT);
        when(machine.getState()).thenReturn(stuckState);
        when(orchestratorProvider.getObject()).thenReturn(orchestrator);
        when(orchestrator.hasActiveMachine("run-dispatch-001")).thenReturn(true);
        when(telegramBotProvider.getIfAvailable()).thenReturn(telegramBot);

        dispatcher.dispatch(machine, MidasState.SECOPS_AUDIT);

        // Durable terminal is still written + machine evicted…
        verify(persistenceService).failRun(eq("run-dispatch-001"), contains("boom"));
        verify(orchestrator).evictRun("run-dispatch-001");
        // …AND — the point of this fix — an honest failure notice is pushed to the originating chat,
        // carrying the real reason, so the user is not left staring at a stale "in progress" message.
        verify(telegramBot).updatePipelineErrorMessage(eq(telegramCtx), contains("boom"));
    }

    @Test
    @DisplayName("CRITICAL_FAILURE accepted on a Telegram run → NO extra chat push (listener owns ERROR)")
    @SuppressWarnings("unchecked")
    void dispatch_criticalFailureAccepted_telegramRun_noExtraChatPush() throws Exception {
        MidasContext telegramCtx = context.withTelegramChatId(555L).withTelegramMessageId(42);
        extendedState.getVariables().put(PipelineContextKeys.MIDAS_CONTEXT, telegramCtx);

        when(agent.execute(telegramCtx)).thenThrow(new RuntimeException("boom"));
        when(machine.sendEvent(any(Mono.class))).thenReturn(Flux.empty());
        // Machine accepted CRITICAL_FAILURE and reached ERROR — the normal path.
        State<MidasState, MidasEvent> errorState = stateOf(MidasState.ERROR);
        when(machine.getState()).thenReturn(errorState);

        dispatcher.dispatch(machine, MidasState.SECOPS_AUDIT);

        // On the accepted path TelegramStateListener renders the failure on ERROR entry, so the
        // dispatcher must NOT push a duplicate — and must not even resolve the bot. (Guards against a
        // regression that pushes on every failure regardless of whether the machine reached ERROR.)
        verify(persistenceService, never()).failRun(anyString(), anyString());
        verifyNoInteractions(telegramBotProvider);
    }

    /** Builds a {@link State} mock whose id is {@code id}, for stubbing {@code machine.getState()}. */
    @SuppressWarnings("unchecked")
    private State<MidasState, MidasEvent> stateOf(MidasState id) {
        State<MidasState, MidasEvent> state = mock(State.class);
        when(state.getId()).thenReturn(id);
        return state;
    }
}
