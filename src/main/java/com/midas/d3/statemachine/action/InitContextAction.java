package com.midas.d3.statemachine.action;

import com.midas.d3.context.AuditEntry;
import com.midas.d3.context.MidasContext;
import com.midas.d3.statemachine.MidasEvent;
import com.midas.d3.statemachine.MidasState;
import com.midas.d3.statemachine.PipelineContextKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.statemachine.StateContext;
import org.springframework.statemachine.action.Action;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Fires on the {@code IDLE → SYSTEM_ANALYSIS} transition triggered by {@link MidasEvent#START}.
 *
 * <p>Reads {@link PipelineContextKeys#RAW_IDEA_HEADER} and
 * {@link PipelineContextKeys#RUN_ID_HEADER} from the event message, creates a fresh
 * {@link MidasContext}, and stores it in {@code ExtendedState}.
 *
 * <p>If headers are missing, a safe fallback context is created with a generated run ID
 * and an empty-idea sentinel so downstream validators fail gracefully rather than NPE.
 */
@Slf4j
@Component
public class InitContextAction implements Action<MidasState, MidasEvent> {

    @Override
    public void execute(StateContext<MidasState, MidasEvent> context) {
        String rawIdea = extractHeader(context, PipelineContextKeys.RAW_IDEA_HEADER, "");
        String runId   = extractHeader(context, PipelineContextKeys.RUN_ID_HEADER, UUID.randomUUID().toString());

        if (rawIdea.isBlank()) {
            log.warn("[InitContextAction] rawIdea header is missing or blank. Pipeline will likely fail at first validation.");
        }

        // ── Telegram fields (null for REST-initiated runs) ────────────────────
        Long    telegramChatId    = extractLongHeader(context, PipelineContextKeys.TELEGRAM_CHAT_ID_HEADER);
        Integer telegramMessageId = extractIntHeader(context, PipelineContextKeys.TELEGRAM_MESSAGE_ID_HEADER);
        boolean autoMode          = Boolean.TRUE.equals(context.getMessageHeader(PipelineContextKeys.AUTO_MODE_HEADER));

        MidasContext midasContext;
        try {
            midasContext = MidasContext.start(
                    rawIdea.isBlank() ? "MISSING_IDEA" : rawIdea,
                    runId);
        } catch (Exception e) {
            log.error("[InitContextAction] Failed to create MidasContext — using fallback.", e);
            midasContext = MidasContext.start("MISSING_IDEA", UUID.randomUUID().toString());
        }

        midasContext = midasContext
                .withTelegramChatId(telegramChatId)
                .withTelegramMessageId(telegramMessageId)
                .appendAudit(AuditEntry.info("IDLE→SYSTEM_ANALYSIS", "Pipeline started"));

        var vars = context.getExtendedState().getVariables();
        vars.put(PipelineContextKeys.MIDAS_CONTEXT, midasContext);
        // Clear the completion latch so a machine reused via RESET → START can deliver again.
        vars.remove(PipelineContextKeys.ARTIFACT_DELIVERY_INITIATED);
        // Clear any residual graceful-degradation state so a reused machine cannot leak a prior run's
        // partial payload or degraded flag into this fresh run (DegradeToGapsAction consumes the
        // DEGRADATION_* keys on its happy path, but never runs if the prior run degraded and reset).
        vars.remove(PipelineContextKeys.DEGRADATION_PARTIAL_SOURCE);
        vars.remove(PipelineContextKeys.DEGRADATION_FEATURE_MANIFEST);
        vars.remove(PipelineContextKeys.DEGRADATION_GAPS);
        vars.remove(PipelineContextKeys.DEGRADED_COMPLETION);
        if (autoMode) {
            vars.put(PipelineContextKeys.AUTO_MODE_KEY, Boolean.TRUE);
        }

        log.info("[InitContextAction] Pipeline run [{}] initialized.", midasContext.getPipelineRunId());
    }

    private String extractHeader(StateContext<MidasState, MidasEvent> context,
                                  String key, String defaultValue) {
        Object raw = context.getMessageHeader(key);
        return (raw instanceof String s && !s.isBlank()) ? s.strip() : defaultValue;
    }

    private Long extractLongHeader(StateContext<MidasState, MidasEvent> context, String key) {
        Object raw = context.getMessageHeader(key);
        if (raw instanceof Long l) return l;
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof String s) {
            try { return Long.parseLong(s.strip()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private Integer extractIntHeader(StateContext<MidasState, MidasEvent> context, String key) {
        Object raw = context.getMessageHeader(key);
        if (raw instanceof Integer i) return i;
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try { return Integer.parseInt(s.strip()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
