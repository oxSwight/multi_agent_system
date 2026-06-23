package com.midas.d3.evolution;

import com.midas.d3.llm.LlmCallRequest;
import com.midas.d3.llm.LlmCallResult;
import com.midas.d3.llm.LlmClient;
import com.midas.d3.llm.LlmModelPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EvolutionAgent model routing")
class EvolutionAgentTest {

    @Mock private LlmClient llmClient;
    @Mock private LlmModelPolicy llmModelPolicy;

    @InjectMocks
    private EvolutionAgent evolutionAgent;

    @Test
    @DisplayName("Routes the non-critical background review to the fast tier (LlmModelPolicy.fastModel())")
    void analyzeCode_usesFastTierModel() {
        when(llmModelPolicy.fastModel()).thenReturn("fast-tier-1b");
        when(llmClient.call(any())).thenReturn(LlmCallResult.of("# Report", "fast-tier-1b", 100, 50));

        String report = evolutionAgent.analyzeCode("public class App {}", "run-evolution-001");

        assertThat(report).isEqualTo("# Report");
        ArgumentCaptor<LlmCallRequest> captor = ArgumentCaptor.forClass(LlmCallRequest.class);
        verify(llmClient).call(captor.capture());
        // Cost-saving: the evolution review must not run on the primary model — it rides the fast tier.
        assertThat(captor.getValue().getModelOverride()).isEqualTo("fast-tier-1b");
        assertThat(captor.getValue().getStage()).isNull();
    }
}
