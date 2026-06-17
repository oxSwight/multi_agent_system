package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import com.midas.d3.context.MidasContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HybridExecutionModel")
class HybridExecutionModelTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
    }

    @Test
    @DisplayName("isHybrid returns true when execution_model is HYBRID")
    void isHybrid_trueForHybridSpec() throws Exception {
        var spec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"HYBRID"}}
                """);
        var ctx = MidasContext.start("Build hybrid app", "run-001").withTechnicalSpec(spec);

        assertThat(HybridExecutionModel.isHybrid(ctx)).isTrue();
        assertThat(HybridExecutionModel.isHybrid(spec)).isTrue();
    }

    @Test
    @DisplayName("isHybrid returns false for CLIENT_SIDE and SERVER_SIDE models")
    void isHybrid_falseForNonHybrid() throws Exception {
        var clientSpec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"CLIENT_SIDE"}}
                """);
        var serverSpec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"SERVER_SIDE"}}
                """);

        assertThat(HybridExecutionModel.isHybrid(clientSpec)).isFalse();
        assertThat(HybridExecutionModel.isHybrid(serverSpec)).isFalse();
    }

    @Test
    @DisplayName("isHybrid returns false when runtime_environment is absent")
    void isHybrid_falseWhenRuntimeMissing() throws Exception {
        var spec = objectMapper.readTree("{\"business_goal\":\"test\"}");
        assertThat(HybridExecutionModel.isHybrid(spec)).isFalse();
    }

    @Test
    @DisplayName("singlePassSurface maps CLIENT_SIDE and SERVER_SIDE to surfaces")
    void singlePassSurface_mapsExecutionModels() throws Exception {
        var clientSpec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"CLIENT_SIDE"}}
                """);
        var serverSpec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"SERVER_SIDE"}}
                """);
        var hybridSpec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"HYBRID"}}
                """);
        var cliSpec = objectMapper.readTree("""
                {"runtime_environment":{"execution_model":"CLI"}}
                """);

        assertThat(HybridExecutionModel.singlePassSurface(clientSpec))
                .contains(ImplementationSurface.CLIENT);
        assertThat(HybridExecutionModel.singlePassSurface(serverSpec))
                .contains(ImplementationSurface.SERVER);
        assertThat(HybridExecutionModel.singlePassSurface(hybridSpec)).isEmpty();
        assertThat(HybridExecutionModel.singlePassSurface(cliSpec)).isEmpty();
    }
}
