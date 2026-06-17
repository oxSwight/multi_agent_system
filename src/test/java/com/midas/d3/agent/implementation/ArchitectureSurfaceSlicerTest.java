package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.midas.d3.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ArchitectureSurfaceSlicer")
class ArchitectureSurfaceSlicerTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new JacksonConfig().objectMapper();
    }

    @Test
    @DisplayName("client slice keeps UI components and client file paths only")
    void slice_clientSurface_filtersServerArtifacts() throws Exception {
        var architecture = objectMapper.readTree("""
                {
                  "architecture_style":"CLIENT_SERVER",
                  "components":[
                    {"name":"popup","type":"UI","responsibility":"UI"},
                    {"name":"TaskController","type":"CONTROLLER","responsibility":"API"}
                  ],
                  "file_layout":[
                    "manifest.json",
                    "src/popup.ts",
                    "src/main/java/com/example/TaskController.java"
                  ],
                  "api_contracts":[{"method":"GET","path":"/api/tasks"}],
                  "data_persistence":{"type":"RELATIONAL","schema":[{"table_name":"tasks","columns":[]}]}
                }
                """);

        var sliced = ArchitectureSurfaceSlicer.slice(architecture, ImplementationSurface.CLIENT, objectMapper);

        assertThat(sliced.get("components")).hasSize(1);
        assertThat(sliced.get("components").get(0).get("name").asText()).isEqualTo("popup");
        assertThat(sliced.get("file_layout")).hasSize(2);
        assertThat(sliced.get("file_layout").get(0).asText()).isEqualTo("manifest.json");
        assertThat(sliced.get("api_contracts")).isEmpty();
        assertThat(sliced.get("data_persistence").get("type").asText()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("server slice keeps controller components and server file paths only")
    void slice_serverSurface_filtersClientArtifacts() throws Exception {
        var architecture = objectMapper.readTree("""
                {
                  "architecture_style":"CLIENT_SERVER",
                  "components":[
                    {"name":"popup","type":"UI","responsibility":"UI"},
                    {"name":"TaskController","type":"CONTROLLER","responsibility":"API"}
                  ],
                  "file_layout":[
                    "manifest.json",
                    "src/popup.ts",
                    "src/main/java/com/example/TaskController.java"
                  ],
                  "api_contracts":[{"method":"GET","path":"/api/tasks"}],
                  "data_persistence":{"type":"RELATIONAL","schema":[{"table_name":"tasks","columns":[]}]}
                }
                """);

        var sliced = ArchitectureSurfaceSlicer.slice(architecture, ImplementationSurface.SERVER, objectMapper);

        assertThat(sliced.get("components")).hasSize(1);
        assertThat(sliced.get("components").get(0).get("name").asText()).isEqualTo("TaskController");
        assertThat(sliced.get("file_layout")).hasSize(1);
        assertThat(sliced.get("file_layout").get(0).asText())
                .isEqualTo("src/main/java/com/example/TaskController.java");
        assertThat(sliced.get("api_contracts")).hasSize(1);
        assertThat(sliced.get("data_persistence").get("type").asText()).isEqualTo("RELATIONAL");
    }

    @Test
    @DisplayName("path heuristics distinguish client and server layouts")
    void pathHeuristics_classifyClientAndServerPaths() {
        assertThat(ArchitectureSurfaceSlicer.isClientPath("src/popup.ts")).isTrue();
        assertThat(ArchitectureSurfaceSlicer.isClientPath("manifest.json")).isTrue();
        assertThat(ArchitectureSurfaceSlicer.isServerPath("src/main/java/com/example/App.java")).isTrue();
        assertThat(ArchitectureSurfaceSlicer.isClientPath("src/main/java/com/example/App.java")).isFalse();
    }
}
