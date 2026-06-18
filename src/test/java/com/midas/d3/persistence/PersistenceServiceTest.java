package com.midas.d3.persistence;

import com.midas.d3.persistence.entity.MidasAgentLogEntity;
import com.midas.d3.persistence.entity.MidasRunEntity;
import com.midas.d3.persistence.repository.MidasAgentLogRepository;
import com.midas.d3.persistence.repository.MidasRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PersistenceService token tracking")
class PersistenceServiceTest {

    @Mock private MidasRunRepository runRepository;
    @Mock private MidasAgentLogRepository agentLogRepository;

    @InjectMocks
    private PersistenceService persistenceService;

    @Test
    @DisplayName("logAgentExecution persists token counts and increments run totals")
    void logAgentExecution_withTokens_persistsAndAggregates() {
        String runId = "run-token-001";
        MidasRunEntity runRef = MidasRunEntity.builder().id(runId).build();
        when(runRepository.getReferenceById(runId)).thenReturn(runRef);

        persistenceService.logAgentExecution(
                runId, "SecOpsAgent", "{\"ok\":true}", 1200, 340, 5000L, false);

        ArgumentCaptor<MidasAgentLogEntity> logCaptor = ArgumentCaptor.forClass(MidasAgentLogEntity.class);
        verify(agentLogRepository).save(logCaptor.capture());
        MidasAgentLogEntity saved = logCaptor.getValue();
        assertThat(saved.getPromptTokens()).isEqualTo(1200);
        assertThat(saved.getCompletionTokens()).isEqualTo(340);

        verify(runRepository).incrementTokenTotals(eq(runId), eq(1200), eq(340), any());
    }

    @Test
    @DisplayName("logAgentExecution skips run total update when both token counts are zero")
    void logAgentExecution_zeroTokens_skipsRunAggregation() {
        String runId = "run-token-002";
        when(runRepository.getReferenceById(runId)).thenReturn(MidasRunEntity.builder().id(runId).build());

        persistenceService.logAgentExecution(
                runId, "SystemAnalystAgent", "error", 0, 0, 100L, true);

        verify(agentLogRepository).save(any(MidasAgentLogEntity.class));
        org.mockito.Mockito.verifyNoMoreInteractions(runRepository);
    }
}
