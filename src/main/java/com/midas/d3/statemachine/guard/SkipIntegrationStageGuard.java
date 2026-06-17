package com.midas.d3.statemachine.guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import com.midas.d3.statemachine.PipelineTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.guard.Guard;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SkipIntegrationStageGuard implements Guard<MidasState, MidasEvent> {

    private final PipelineTopology topology;

    @Override
    public boolean evaluate(StateContext<MidasState, MidasEvent> context) {
        var vars = context.getExtendedState().getVariables();
        Object node = vars.get(PipelineContextKeys.LAST_VALIDATED_NODE);
        if (!(node instanceof JsonNode validated)) {
            return false;
        }
        MidasContext midasContext = (MidasContext) vars.get(PipelineContextKeys.MIDAS_CONTEXT);
        if (midasContext == null) {
            return false;
        }
        boolean skip = topology.shouldSkipIntegrationStage(validated, midasContext);
        log.debug("[SkipIntegrationStageGuard] skipIntegrationStage={}", skip);
        return skip;
    }
}
