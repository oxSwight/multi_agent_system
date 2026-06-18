package com.midas.d3.evolution;

import com.midas.d3.llm.LlmCallRequest;
import com.midas.d3.llm.LlmCallResult;
import com.midas.d3.llm.LlmClient;
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

    @InjectMocks
    private EvolutionAgent evolutionAgent;

    @Test
    @DisplayName("Uses default Flash model via LlmClient.defaultModelId(), not stage map")
    void analyzeCode_usesDefaultModelOverride() {
        when(llmClient.defaultModelId()).thenReturn("gemini-1.5-flash");
        when(llmClient.call(any())).thenReturn(LlmCallResult.of("# Report", "gemini-1.5-flash", 100, 50));

        String report = evolutionAgent.analyzeCode("public class App {}", "run-evolution-001");

        assertThat(report).isEqualTo("# Report");
        ArgumentCaptor<LlmCallRequest> captor = ArgumentCaptor.forClass(LlmCallRequest.class);
        verify(llmClient).call(captor.capture());
        assertThat(captor.getValue().getModelOverride()).isEqualTo("gemini-1.5-flash");
        assertThat(captor.getValue().getStage()).isNull();
    }
}
