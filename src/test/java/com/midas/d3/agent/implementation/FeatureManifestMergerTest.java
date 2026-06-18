package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FeatureManifestMerger")
class FeatureManifestMergerTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
    }

    @Test
    @DisplayName("merge combines disjoint client and server manifest entries")
    void merge_disjointFeatureIds_producesUnifiedArray() throws Exception {
        var client = objectMapper.readTree("""
                [
                  {
                    "feature_id": "popup-ui",
                    "feature_name": "Popup UI",
                    "files": ["src/popup.ts"],
                    "entry_points": ["renderPopup"]
                  }
                ]
                """);
        var server = objectMapper.readTree("""
                [
                  {
                    "feature_id": "tasks-api",
                    "feature_name": "Tasks API",
                    "files": ["src/main/java/com/example/TaskController.java"],
                    "entry_points": ["TaskController"]
                  }
                ]
                """);

        var merged = FeatureManifestMerger.merge(client, server, objectMapper);

        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).get("feature_id").asText()).isEqualTo("popup-ui");
        assertThat(merged.get(1).get("feature_id").asText()).isEqualTo("tasks-api");
    }

    @Test
    @DisplayName("merge deduplicates identical feature_id entries across passes")
    void merge_identicalFeatureId_deduplicates() throws Exception {
        var client = objectMapper.readTree("""
                [
                  {
                    "feature_id": "shared-feature",
                    "feature_name": "Shared feature",
                    "files": ["shared/config.json"],
                    "entry_points": ["configure"]
                  }
                ]
                """);
        var server = objectMapper.readTree("""
                [
                  {
                    "feature_id": "shared-feature",
                    "feature_name": "Shared feature",
                    "files": ["shared/config.json"],
                    "entry_points": ["configure"]
                  }
                ]
                """);

        var merged = FeatureManifestMerger.merge(client, server, objectMapper);

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).get("feature_id").asText()).isEqualTo("shared-feature");
    }

    @Test
    @DisplayName("merge rejects conflicting entries for the same feature_id")
    void merge_conflictingFeatureId_throwsMergeException() throws Exception {
        var client = objectMapper.readTree("""
                [
                  {
                    "feature_id": "shared-feature",
                    "feature_name": "Client version",
                    "files": ["client/config.json"],
                    "entry_points": ["clientConfigure"]
                  }
                ]
                """);
        var server = objectMapper.readTree("""
                [
                  {
                    "feature_id": "shared-feature",
                    "feature_name": "Server version",
                    "files": ["server/config.json"],
                    "entry_points": ["serverConfigure"]
                  }
                ]
                """);

        assertThatThrownBy(() -> FeatureManifestMerger.merge(client, server, objectMapper))
                .isInstanceOf(FeatureManifestMerger.FeatureManifestMergeException.class)
                .hasMessageContaining("conflicting entries");
    }

    @Test
    @DisplayName("merge rejects empty manifest array")
    void merge_emptyManifest_throwsMergeException() throws Exception {
        var client = objectMapper.readTree("[]");
        var server = objectMapper.readTree("""
                [
                  {
                    "feature_id": "tasks-api",
                    "feature_name": "Tasks API",
                    "files": ["App.java"],
                    "entry_points": ["App"]
                  }
                ]
                """);

        assertThatThrownBy(() -> FeatureManifestMerger.merge(client, server, objectMapper))
                .isInstanceOf(FeatureManifestMerger.FeatureManifestMergeException.class)
                .hasMessageContaining("empty");
    }
}
