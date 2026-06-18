package com.midas.d3.agent.base;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.agent.impl.SystemAnalystAgent;
import com.midas.d3.context.AgentContextView;
import com.midas.d3.context.ContextReducer;
import com.midas.d3.context.MidasContext;
import com.midas.d3.llm.LlmCallException;
import com.midas.d3.llm.LlmCallRequest;
import com.midas.d3.llm.LlmCallResult;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.llm.LlmModelPolicy;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.ValidatorRegistry;
import com.midas.d3.validation.GoalKeeperValidator;
import com.midas.d3.validation.ValidationHookException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link BaseMidasAgent} — specifically the Template Method execution
 * protocol, internal retry loop, and error propagation logic.
 *
 * <p>Uses {@link SystemAnalystAgent} as the concrete subject under test because
 * it is the simplest agent (no upstream artifact dependencies).
 *
 * <p>All I/O dependencies ({@link LlmClient}, {@link ContextReducer},
 * {@link ValidatorRegistry}, {@link GoalKeeperValidator}) are mocked with Mockito.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BaseMidasAgent — Template Method & Retry Logic")
class BaseMidasAgentTest {

    // ── Mocks ─────────────────────────────────────────────────────────────────

    @Mock private LlmClient           llmClient;
    @Mock private ContextReducer      contextReducer;
    @Mock private ValidatorRegistry   validatorRegistry;
    @Mock private GoalKeeperValidator goalKeeperValidator;
    @Mock private LlmModelPolicy      llmModelPolicy;

    // ── Subject Under Test ────────────────────────────────────────────────────

    private SystemAnalystAgent agent;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static final String RUN_ID   = "run-test-001";
    private static final String USER_IDEA = "Build a task management system";

    private static final String VALID_JSON = """
            {
              "business_goal": "Help teams manage tasks",
              "core_features": ["Create task", "Delete task", "Assign task"],
              "edge_cases_and_handling": [{"case": "empty title", "solution": "return 400"}],
              "performance_constraints": ["p99 < 200ms"]
            }""";

    private static final String INVALID_JSON = "{ \"wrong_field\": \"value\" }";

    private MidasContext context;
    private AgentContextView contextView;

    @BeforeEach
    void setUp() throws Exception {
        agent = new SystemAnalystAgent(llmClient, contextReducer, validatorRegistry, llmModelPolicy);

        context = MidasContext.start(USER_IDEA, RUN_ID);
        contextView = AgentContextView.builder()
                .agentName("SYSTEM_ANALYST")
                .pipelineRunId(RUN_ID)
                .rawUserIdea(USER_IDEA)
                .requiredArtifacts(Collections.emptyMap())
                .estimatedTokenBudget(50)
                .build();

        // Common stubs
        when(contextReducer.reduce(any(MidasContext.class), eq(ContextReducer.AgentRole.SYSTEM_ANALYST)))
                .thenReturn(contextView);
        when(validatorRegistry.getValidator(MidasState.SYSTEM_ANALYSIS))
                .thenReturn(Optional.of(goalKeeperValidator));
        lenient().when(llmModelPolicy.resolve(MidasState.SYSTEM_ANALYSIS)).thenReturn("gemini-1.5-flash");
    }

    // ── Happy Path ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Happy path — first attempt success")
    class HappyPath {

        @BeforeEach
        void stubSuccess() throws Exception {
            JsonNode validNode = new ObjectMapper().readTree(VALID_JSON);
            when(llmClient.call(any(LlmCallRequest.class))).thenReturn(LlmCallResult.ofText(VALID_JSON));
            when(goalKeeperValidator.validate(anyString())).thenReturn(validNode);
        }

        @Test
        @DisplayName("Returns AgentResult with attemptsUsed=1 on first-attempt success")
        void execute_firstAttemptSuccess_returnsResultWithOneAttempt() {
            AgentResult result = agent.execute(context);

            assertThat(result).isNotNull();
            assertThat(result.attemptsUsed()).isEqualTo(1);
            assertThat(result.succeededOnFirstAttempt()).isTrue();
            assertThat(result.validatedOutput()).isNotNull();
        }

        @Test
        @DisplayName("LLM is called exactly once when first attempt succeeds")
        void execute_firstAttemptSuccess_llmCalledOnce() {
            agent.execute(context);

            verify(llmClient, times(1)).call(any(LlmCallRequest.class));
        }

        @Test
        @DisplayName("LLM request carries correct agentName, stage, and pipelineRunId")
        void execute_llmRequestHasCorrectMetadata() {
            agent.execute(context);

            ArgumentCaptor<LlmCallRequest> captor = ArgumentCaptor.forClass(LlmCallRequest.class);
            verify(llmClient).call(captor.capture());

            LlmCallRequest req = captor.getValue();
            assertThat(req.getAgentName()).isEqualTo("SystemAnalystAgent");
            assertThat(req.getStage()).isEqualTo(MidasState.SYSTEM_ANALYSIS);
            assertThat(req.getPipelineRunId()).isEqualTo(RUN_ID);
            assertThat(req.getModelOverride()).isEqualTo("gemini-1.5-flash");
            assertThat(req.getSystemPrompt()).isNotBlank();
            assertThat(req.getUserMessage()).contains(USER_IDEA);
        }

        @Test
        @DisplayName("GoalKeeperValidator is called with the sanitized LLM output")
        void execute_validatorCalledWithSanitizedOutput() throws Exception {
            agent.execute(context);

            // Validator must be invoked with the sanitized string
            verify(goalKeeperValidator, times(1)).validate(anyString());
        }
    }

    // ── Retry on Validation Failure ───────────────────────────────────────────

    @Nested
    @DisplayName("Retry on GoalKeeper validation failure")
    class RetryOnValidationFailure {

        @Test
        @DisplayName("Retries once and succeeds on 2nd attempt when 1st fails validation")
        void execute_firstAttemptInvalidJson_retriesAndSucceedsOnSecond() throws Exception {
            JsonNode validNode = new ObjectMapper().readTree(VALID_JSON);
            ValidationHookException ve = new ValidationHookException(
                    "SystemAnalyst", "SYSTEM_ANALYSIS",
                    List.of("Missing required field: 'business_goal'"));

            // Attempt 1: invalid → Attempt 2: valid
            when(llmClient.call(any())).thenReturn(LlmCallResult.ofText(INVALID_JSON), LlmCallResult.ofText(VALID_JSON));
            when(goalKeeperValidator.validate(anyString()))
                    .thenThrow(ve)
                    .thenReturn(validNode);

            AgentResult result = agent.execute(context);

            assertThat(result.attemptsUsed()).isEqualTo(2);
            assertThat(result.succeededOnFirstAttempt()).isFalse();
            verify(llmClient, times(2)).call(any());
        }

        @Test
        @DisplayName("Retries twice and succeeds on 3rd attempt when first two fail validation")
        void execute_twoFailsThenSuccess_returnsThirdAttempt() throws Exception {
            JsonNode validNode = new ObjectMapper().readTree(VALID_JSON);
            ValidationHookException ve = new ValidationHookException(
                    "SystemAnalyst", "SYSTEM_ANALYSIS",
                    List.of("Missing required field: 'core_features'"));

            when(llmClient.call(any())).thenReturn(LlmCallResult.ofText(INVALID_JSON), LlmCallResult.ofText(INVALID_JSON), LlmCallResult.ofText(VALID_JSON));
            when(goalKeeperValidator.validate(anyString()))
                    .thenThrow(ve)
                    .thenThrow(ve)
                    .thenReturn(validNode);

            AgentResult result = agent.execute(context);

            assertThat(result.attemptsUsed()).isEqualTo(3);
            verify(llmClient, times(3)).call(any());
        }

        @Test
        @DisplayName("2nd attempt user message contains correction feedback with validation errors")
        void execute_onRetry_userMessageContainsCorrectionFeedback() throws Exception {
            JsonNode validNode = new ObjectMapper().readTree(VALID_JSON);
            String violation = "Missing required field: 'core_features'";
            ValidationHookException ve = new ValidationHookException(
                    "SystemAnalyst", "SYSTEM_ANALYSIS", List.of(violation));

            when(llmClient.call(any())).thenReturn(LlmCallResult.ofText(INVALID_JSON), LlmCallResult.ofText(VALID_JSON));
            when(goalKeeperValidator.validate(anyString()))
                    .thenThrow(ve)
                    .thenReturn(validNode);

            agent.execute(context);

            ArgumentCaptor<LlmCallRequest> captor = ArgumentCaptor.forClass(LlmCallRequest.class);
            verify(llmClient, times(2)).call(captor.capture());

            List<LlmCallRequest> calls = captor.getAllValues();
            // First call: no correction feedback
            assertThat(calls.get(0).getUserMessage()).doesNotContain("CORRECTION REQUIRED");
            // Second call: has correction feedback with the violation message
            assertThat(calls.get(1).getUserMessage())
                    .contains("CORRECTION REQUIRED")
                    .contains(violation);
        }

        @Test
        @DisplayName("Throws AgentExecutionException after 3 consecutive validation failures")
        void execute_threeValidationFailures_throwsAgentExecutionException() {
            ValidationHookException ve = new ValidationHookException(
                    "SystemAnalyst", "SYSTEM_ANALYSIS",
                    List.of("Missing 'business_goal'", "Missing 'core_features'"));

            when(llmClient.call(any())).thenReturn(LlmCallResult.ofText(INVALID_JSON));
            when(goalKeeperValidator.validate(anyString())).thenThrow(ve);

            assertThatThrownBy(() -> agent.execute(context))
                    .isInstanceOf(AgentExecutionException.class)
                    .hasMessageContaining("SystemAnalyst")
                    .hasMessageContaining("3");

            verify(llmClient, times(BaseMidasAgent.MAX_AGENT_RETRIES)).call(any());
        }

        @Test
        @DisplayName("AgentExecutionException carries correct agentName and role")
        void execute_exhaustedRetries_exceptionHasCorrectMetadata() {
            ValidationHookException ve = new ValidationHookException(
                    "SystemAnalyst", "SYSTEM_ANALYSIS", "bad JSON");

            when(llmClient.call(any())).thenReturn(LlmCallResult.ofText(INVALID_JSON));
            when(goalKeeperValidator.validate(anyString())).thenThrow(ve);

            AgentExecutionException ex = catchThrowableOfType(
                    () -> agent.execute(context), AgentExecutionException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getAgentName()).isEqualTo("SystemAnalystAgent");
            assertThat(ex.getRole()).isEqualTo(ContextReducer.AgentRole.SYSTEM_ANALYST);
            assertThat(ex.getMaxAttempts()).isEqualTo(BaseMidasAgent.MAX_AGENT_RETRIES);
        }
    }

    // ── Retry on Network / LLM Transport Errors ───────────────────────────────

    @Nested
    @DisplayName("Retry on retryable LlmCallException (network / rate-limit)")
    class RetryOnLlmCallException {

        @Test
        @DisplayName("Retries after retryable timeout and succeeds on 2nd attempt")
        void execute_timeoutOnFirstAttempt_retriesAndSucceeds() throws Exception {
            JsonNode validNode = new ObjectMapper().readTree(VALID_JSON);
            when(llmClient.call(any()))
                    .thenThrow(LlmCallException.timeout("SystemAnalyst"))
                    .thenReturn(LlmCallResult.ofText(VALID_JSON));
            when(goalKeeperValidator.validate(anyString())).thenReturn(validNode);

            AgentResult result = agent.execute(context);

            assertThat(result.attemptsUsed()).isEqualTo(2);
            verify(llmClient, times(2)).call(any());
        }

        @Test
        @DisplayName("Retries after retryable rate-limit (429) and succeeds on 2nd attempt")
        void execute_rateLimitedOnFirstAttempt_retriesAndSucceeds() throws Exception {
            JsonNode validNode = new ObjectMapper().readTree(VALID_JSON);
            when(llmClient.call(any()))
                    .thenThrow(LlmCallException.rateLimited("SystemAnalyst"))
                    .thenReturn(LlmCallResult.ofText(VALID_JSON));
            when(goalKeeperValidator.validate(anyString())).thenReturn(validNode);

            AgentResult result = agent.execute(context);

            assertThat(result.attemptsUsed()).isEqualTo(2);
        }

        @Test
        @DisplayName("Retries after retryable server error (5xx) and succeeds on 3rd attempt")
        void execute_serverErrorTwice_retriesAndSucceedsOnThird() throws Exception {
            JsonNode validNode = new ObjectMapper().readTree(VALID_JSON);
            LlmCallException serverErr = LlmCallException.serverError("SystemAnalyst", 503);

            when(llmClient.call(any()))
                    .thenThrow(serverErr)
                    .thenThrow(serverErr)
                    .thenReturn(LlmCallResult.ofText(VALID_JSON));
            when(goalKeeperValidator.validate(anyString())).thenReturn(validNode);

            AgentResult result = agent.execute(context);

            assertThat(result.attemptsUsed()).isEqualTo(3);
            verify(llmClient, times(3)).call(any());
        }

        @Test
        @DisplayName("Throws AgentExecutionException after 3 consecutive retryable network errors")
        void execute_threeNetworkErrors_throwsAgentExecutionException() {
            LlmCallException timeout = LlmCallException.timeout("SystemAnalyst");
            when(llmClient.call(any())).thenThrow(timeout);

            assertThatThrownBy(() -> agent.execute(context))
                    .isInstanceOf(AgentExecutionException.class)
                    .hasMessageContaining("SystemAnalyst");

            verify(llmClient, times(BaseMidasAgent.MAX_AGENT_RETRIES)).call(any());
        }

        @Test
        @DisplayName("Non-retryable rate-limit exhaustion propagates immediately without retrying")
        void execute_rateLimitExhausted_propagatesImmediatelyNoRetry() {
            LlmCallException exhausted = LlmCallException.rateLimitExhausted("Gemini", "SystemAnalyst");
            when(llmClient.call(any())).thenThrow(exhausted);

            assertThatThrownBy(() -> agent.execute(context))
                    .isInstanceOf(LlmCallException.class)
                    .hasMessageContaining("Gemini API Rate Limit Exceeded");

            verify(llmClient, times(1)).call(any());
        }

        @Test
        @DisplayName("Non-retryable LlmCallException is propagated immediately without retrying")
        void execute_nonRetryableLlmError_propagatesImmediatelyNoRetry() {
            // HTTP 400 bad request — non-retryable
            LlmCallException badRequest = new LlmCallException(
                    "HTTP 400: Invalid request for agent [SystemAnalyst]", 400, false);

            when(llmClient.call(any())).thenThrow(badRequest);

            assertThatThrownBy(() -> agent.execute(context))
                    .isInstanceOf(LlmCallException.class)
                    .hasMessageContaining("400");

            // Must NOT retry — only 1 call allowed
            verify(llmClient, times(1)).call(any());
        }
    }

    // ── Mixed Failure Scenarios ───────────────────────────────────────────────

    @Nested
    @DisplayName("Mixed failure scenarios")
    class MixedFailures {

        @Test
        @DisplayName("Network error on attempt 1, validation fail on attempt 2, success on attempt 3")
        void execute_networkThenValidationFailThenSuccess() throws Exception {
            JsonNode validNode = new ObjectMapper().readTree(VALID_JSON);
            ValidationHookException ve = new ValidationHookException(
                    "SystemAnalyst", "SYSTEM_ANALYSIS", "bad schema");

            when(llmClient.call(any()))
                    .thenThrow(LlmCallException.timeout("SystemAnalyst"))
                    .thenReturn(LlmCallResult.ofText(INVALID_JSON))
                    .thenReturn(LlmCallResult.ofText(VALID_JSON));

            when(goalKeeperValidator.validate(anyString()))
                    .thenThrow(ve)
                    .thenReturn(validNode);

            AgentResult result = agent.execute(context);

            assertThat(result.attemptsUsed()).isEqualTo(3);
        }
    }

    // ── AgentResult contract ──────────────────────────────────────────────────

    @Nested
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("AgentResult value contract")
    class AgentResultContract {

        @Test
        @DisplayName("AgentResult accumulates token usage across retry attempts")
        void execute_retries_accumulatesTokenUsage() throws Exception {
            JsonNode validNode = new ObjectMapper().readTree(VALID_JSON);
            ValidationHookException ve = new ValidationHookException(
                    "SystemAnalyst", "SYSTEM_ANALYSIS",
                    List.of("Missing required field: 'business_goal'"));

            when(llmClient.call(any()))
                    .thenReturn(LlmCallResult.of("bad", "test-model", 100, 20))
                    .thenReturn(LlmCallResult.of(VALID_JSON, "test-model", 200, 40));
            when(goalKeeperValidator.validate(anyString()))
                    .thenThrow(ve)
                    .thenReturn(validNode);

            AgentResult result = agent.execute(context);

            assertThat(result.promptTokens()).isEqualTo(300);
            assertThat(result.completionTokens()).isEqualTo(60);
            assertThat(result.modelId()).isEqualTo("test-model");
        }

        @Test
        @DisplayName("AgentResult is non-null with correct shape on success")
        void agentResult_contractOnSuccess() throws Exception {
            JsonNode validNode = new ObjectMapper().readTree(VALID_JSON);
            when(llmClient.call(any())).thenReturn(LlmCallResult.ofText(VALID_JSON));
            when(goalKeeperValidator.validate(anyString())).thenReturn(validNode);

            AgentResult result = agent.execute(context);

            assertThat(result.validatedOutput()).isSameAs(validNode);
            assertThat(result.rawLlmOutput()).isNotBlank();
            assertThat(result.attemptsUsed()).isPositive();
        }

        @Test
        @DisplayName("AgentResult allows null validatedOutput for [NEED_INFO] responses")
        void agentResult_nullOutput_allowedForNeedsInfo() {
            // Since Stage 8 (Human-in-the-Loop), validatedOutput may be null when the
            // analyst returns a [NEED_INFO] response. Use the dedicated factory method.
            AgentResult result = AgentResult.needsInfo("[NEED_INFO]\n1. What is the data source?", 1);
            assertThat(result.validatedOutput()).isNull();
            assertThat(result.isNeedsInfo()).isTrue();
            assertThat(result.attemptsUsed()).isEqualTo(1);
        }

        @Test
        @DisplayName("AgentResult constructor throws when attemptsUsed < 1")
        void agentResult_zeroAttempts_throwsIllegalArgument() throws Exception {
            JsonNode node = new ObjectMapper().readTree("{}");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new AgentResult(node, "raw", 0));
        }
    }

    // ── Infrastructure guard ──────────────────────────────────────────────────

    @Test
    @DisplayName("Throws IllegalStateException when no validator registered for stage")
    void execute_noValidatorRegistered_throwsIllegalState() {
        when(validatorRegistry.getValidator(MidasState.SYSTEM_ANALYSIS))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> agent.execute(context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SYSTEM_ANALYSIS");

        // LLM must NOT be called — guard fires before the first LLM request
        verifyNoInteractions(llmClient);
    }
}
