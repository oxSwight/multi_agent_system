package com.midas.d3.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import com.midas.d3.context.MidasContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineContextResponseTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
    }

    @Test
    void from_mapsRemediationFieldsWhenPresent() throws Exception {
        var directive = objectMapper.readTree("""
                {"source_verdict":"REJECT","required_changes":["Add auth"],"remediation_attempt":1}
                """);
        var ctx = MidasContext.start("Build an app", "run-001")
                .withProductReviewRemediationAttempts(1)
                .withRemediationDirective(directive);

        PipelineContextResponse response = PipelineContextResponse.from(ctx, "CODE_GENERATION");

        assertThat(response.productReviewRemediationAttempts()).isEqualTo(1);
        assertThat(response.remediationDirective()).isEqualTo(directive);
        assertThat(response.runId()).isEqualTo("run-001");
        assertThat(response.state()).isEqualTo("CODE_GENERATION");
    }

    @Test
    void from_mapsZeroAttemptsAndNullDirectiveByDefault() {
        var ctx = MidasContext.start("Build an app", "run-002");

        PipelineContextResponse response = PipelineContextResponse.from(ctx, "SYSTEM_ANALYSIS");

        assertThat(response.productReviewRemediationAttempts()).isZero();
        assertThat(response.remediationDirective()).isNull();
    }
}
