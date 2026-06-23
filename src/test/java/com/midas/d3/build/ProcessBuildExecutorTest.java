package com.midas.d3.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the two-phase (compile → test) build logic and its {@link BuildPhase} attribution,
 * driven by a deterministic fake {@link ProcessRunner} — no real toolchain is ever invoked.
 */
@DisplayName("ProcessBuildExecutor — two-phase compile/test")
class ProcessBuildExecutorTest {

    private final Path dir = Path.of("sandbox");
    private final ObjectMapper mapper = new ObjectMapper();

    /** Returns queued outcomes in order and records the commands it was asked to run. */
    private static final class FakeRunner implements ProcessRunner {
        final Deque<ProcessOutcome> outcomes = new ArrayDeque<>();
        final List<List<String>> commands = new ArrayList<>();

        FakeRunner give(int exitCode, String output) {
            outcomes.add(new ProcessOutcome(exitCode, output));
            return this;
        }

        @Override
        public ProcessOutcome run(Path workingDir, List<String> command, long timeoutSeconds) {
            commands.add(command);
            if (outcomes.isEmpty()) {
                throw new AssertionError("no queued outcome for command " + command);
            }
            return outcomes.poll();
        }
    }

    private ProcessBuildExecutor executor(FakeRunner runner) {
        return new ProcessBuildExecutor(runner);
    }

    @Test
    @DisplayName("compile fails → COMPILE-phase failure and the test phase is skipped")
    void compileFailure_skipsTestPhase() {
        FakeRunner runner = new FakeRunner().give(1, "App.java:[3,5] error: cannot find symbol");
        BuildReport report = executor(runner).execute(dir, BuildTool.MAVEN);

        assertThat(report.success()).isFalse();
        assertThat(report.failurePhase()).isEqualTo(BuildPhase.COMPILE);
        assertThat(report.compiled()).isFalse();
        assertThat(report.testsPassed()).isFalse();
        assertThat(report.diagnostics()).isNotEmpty();
        assertThat(runner.commands).hasSize(1); // test phase never ran
    }

    @Test
    @DisplayName("compile passes but tests fail → TEST-phase failure; the code is still marked compiled")
    void testFailure_isDistinctFromCompile() {
        FakeRunner runner = new FakeRunner()
                .give(0, "BUILD SUCCESS")              // compile phase
                .give(1, "Tests run: 3, Failures: 1"); // test phase
        BuildReport report = executor(runner).execute(dir, BuildTool.MAVEN);

        assertThat(report.success()).isFalse();
        assertThat(report.failurePhase()).isEqualTo(BuildPhase.TEST);
        assertThat(report.compiled()).isTrue();     // it DID compile
        assertThat(report.testsPassed()).isFalse();
        assertThat(runner.commands).hasSize(2);
    }

    @Test
    @DisplayName("compile and tests pass → SUCCESS with no failure phase")
    void allPass_isSuccess() {
        FakeRunner runner = new FakeRunner().give(0, "ok").give(0, "Tests run: 3, Failures: 0");
        BuildReport report = executor(runner).execute(dir, BuildTool.MAVEN);

        assertThat(report.success()).isTrue();
        assertThat(report.failurePhase()).isNull();
        assertThat(report.compiled()).isTrue();
        assertThat(report.testsPassed()).isTrue();
        assertThat(runner.commands).hasSize(2);
    }

    @Test
    @DisplayName("runs a distinct compile command, then a test command")
    void runsCompileThenTest() {
        FakeRunner runner = new FakeRunner().give(0, "ok").give(0, "ok");
        executor(runner).execute(dir, BuildTool.MAVEN);

        assertThat(runner.commands.get(0)).contains("test-compile");
        assertThat(runner.commands.get(1)).contains("test").doesNotContain("test-compile");
    }

    @Test
    @DisplayName("failure_phase is surfaced in the JSON artifact only on failure")
    void jsonExposesFailurePhaseOnlyOnFailure() {
        BuildReport pass = executor(new FakeRunner().give(0, "ok").give(0, "ok")).execute(dir, BuildTool.MAVEN);
        assertThat(pass.toJson(mapper).has("failure_phase")).isFalse();

        BuildReport testFail = executor(new FakeRunner().give(0, "ok").give(1, "Tests run: 1, Failures: 1"))
                .execute(dir, BuildTool.MAVEN);
        assertThat(testFail.toJson(mapper).get("failure_phase").asText()).isEqualTo("TEST");
    }
}
