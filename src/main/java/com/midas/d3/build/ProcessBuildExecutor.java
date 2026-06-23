package com.midas.d3.build;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Production {@link BuildExecutor} that drives the project's real toolchain
 * ({@code mvn} / {@code gradle} / {@code npm}) through a {@link ProcessRunner}, in two distinct
 * phases so a failure is attributed to the right {@link BuildPhase}:
 *
 * <ol>
 *   <li><b>COMPILE</b> — compile main + test sources <em>without</em> running tests.</li>
 *   <li><b>TEST</b> — run the compiled/generated test suite.</li>
 * </ol>
 *
 * <p>A non-zero compile step returns a {@link BuildPhase#COMPILE} failure and the test step is
 * skipped; a non-zero test step returns a {@link BuildPhase#TEST} failure (the code compiled but its
 * tests don't pass). This is what lets the self-healing loop and the quality harness distinguish
 * "won't compile" from "compiles but fails its tests." Only a missing toolchain / timeout raises
 * {@link BuildExecutionException}.
 *
 * <p>The per-tool {@link Commands} mapping is the extension seam for future stacks (e.g. a Python
 * {@code python -m pytest} test phase): add a {@link BuildTool} and its compile/test command lines.
 *
 * <p>Never exercised by the unit/integration suite against a live toolchain — the phase logic is
 * unit-tested with a fake {@link ProcessRunner}, and higher layers inject a fake {@link BuildExecutor}.
 */
@Slf4j
@Component
public class ProcessBuildExecutor implements BuildExecutor {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final ProcessRunner processRunner;

    @Value("${midas.build.timeout-seconds:180}")
    private long timeoutSeconds;

    public ProcessBuildExecutor(ProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    @Override
    public BuildReport execute(Path projectDir, BuildTool tool) {
        Commands commands = commandsFor(tool);

        // ── Phase 1: COMPILE ────────────────────────────────────────────────────
        ProcessRunner.ProcessOutcome compile = processRunner.run(projectDir, commands.compile(), timeoutSeconds);
        if (compile.exitCode() != 0) {
            log.info("[ProcessBuildExecutor] {} COMPILE failed (exit {}).", tool, compile.exitCode());
            return BuildReport.failure(
                    tool, compile.exitCode(),
                    BuildDiagnosticParser.parse(tool, compile.output()),
                    tool + " compilation failed with exit code " + compile.exitCode() + ".",
                    compile.output(), BuildPhase.COMPILE);
        }

        // ── Phase 2: TEST (the code compiled — now prove it behaves) ─────────────
        ProcessRunner.ProcessOutcome test = processRunner.run(projectDir, commands.test(), timeoutSeconds);
        if (test.exitCode() != 0) {
            log.info("[ProcessBuildExecutor] {} compiled but TEST failed (exit {}).", tool, test.exitCode());
            return BuildReport.failure(
                    tool, test.exitCode(),
                    BuildDiagnosticParser.parse(tool, test.output()),
                    tool + " tests failed with exit code " + test.exitCode() + ".",
                    test.output(), BuildPhase.TEST);
        }

        return BuildReport.success(tool, tool + " compiled and tests passed.");
    }

    /** Compile + test command lines for a build tool. */
    record Commands(List<String> compile, List<String> test) {
    }

    private Commands commandsFor(BuildTool tool) {
        return switch (tool) {
            case MAVEN -> new Commands(
                    List.of(exe("mvn"), "-B", "-q", "-e", "-DskipTests", "test-compile"),
                    List.of(exe("mvn"), "-B", "-q", "-e", "test"));
            case GRADLE -> new Commands(
                    List.of(exe("gradle"), "--quiet", "compileTestJava"),
                    List.of(exe("gradle"), "--quiet", "test"));
            case NPM -> new Commands(
                    List.of(exe("npm"), "run", "build"),
                    List.of(exe("npm"), "test"));
            case NONE -> throw new BuildExecutionException("No build tool to execute for NONE.");
        };
    }

    /** Windows resolves {@code mvn}/{@code npm}/{@code gradle} via their {@code .cmd} shims. */
    private static String exe(String base) {
        return WINDOWS ? base + ".cmd" : base;
    }
}
