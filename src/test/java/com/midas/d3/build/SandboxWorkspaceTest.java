package com.midas.d3.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SandboxWorkspace — containment")
class SandboxWorkspaceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode map(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("materializes safe files within the root")
    void materializesSafeFiles() throws Exception {
        try (SandboxWorkspace ws = SandboxWorkspace.create("t1")) {
            int n = ws.materialize(map("{\"pom.xml\":\"<project/>\",\"src/main/java/App.java\":\"class App{}\"}"));
            assertThat(n).isEqualTo(2);
            assertThat(Files.exists(ws.root().resolve("pom.xml"))).isTrue();
            assertThat(Files.exists(ws.root().resolve("src/main/java/App.java"))).isTrue();
        }
    }

    @Test
    @DisplayName("skips a ../ traversal path and never writes outside the root")
    void skipsTraversal() throws Exception {
        try (SandboxWorkspace ws = SandboxWorkspace.create("t2")) {
            String marker = "escape-" + ws.root().getFileName() + ".txt";
            Path escapeTarget = ws.root().getParent().resolve(marker);
            Files.deleteIfExists(escapeTarget);

            int n = ws.materialize(map("{\"../" + marker + "\":\"pwned\",\"ok.txt\":\"safe\"}"));

            assertThat(n).isEqualTo(1);
            assertThat(Files.exists(ws.root().resolve("ok.txt"))).isTrue();
            assertThat(Files.notExists(escapeTarget)).isTrue();
        }
    }

    @Test
    @DisplayName("skips an absolute path")
    void skipsAbsolute() throws Exception {
        try (SandboxWorkspace ws = SandboxWorkspace.create("t3")) {
            Path abs = ws.root().resolveSibling("midas-abs-escape-" + ws.root().getFileName() + ".txt");
            Files.deleteIfExists(abs);
            String key = abs.toString().replace("\\", "\\\\"); // JSON-escape Windows backslashes

            int n = ws.materialize(map("{\"" + key + "\":\"pwned\",\"fine.txt\":\"ok\"}"));

            assertThat(n).isEqualTo(1);
            assertThat(Files.notExists(abs)).isTrue();
        }
    }

    @Test
    @DisplayName("close() deletes the workspace tree")
    void closeDeletes() throws Exception {
        Path root;
        try (SandboxWorkspace ws = SandboxWorkspace.create("t4")) {
            ws.materialize(map("{\"a.txt\":\"x\"}"));
            root = ws.root();
            assertThat(Files.exists(root)).isTrue();
        }
        assertThat(Files.exists(root)).isFalse();
    }

    @Test
    @DisplayName("enforces the file-count cap")
    void enforcesFileCap() throws Exception {
        ObjectNode node = mapper.createObjectNode();
        for (int i = 0; i < SandboxWorkspace.MAX_FILES + 10; i++) {
            node.put("f" + i + ".txt", "x");
        }
        try (SandboxWorkspace ws = SandboxWorkspace.create("t5")) {
            assertThat(ws.materialize(node)).isEqualTo(SandboxWorkspace.MAX_FILES);
        }
    }
}
