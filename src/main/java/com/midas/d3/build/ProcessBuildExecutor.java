package com.midas.d3.build;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Production {@link BuildExecutor} that shells out to the project's real toolchain
 * ({@code mvn} / {@code gradle} / {@code npm}) inside the materialized sandbox.
 *
 * <p>It compiles only — tests are skipped here ({@code -DskipTests}) because the
 * {@code BUILD_VERIFICATION} gate's job is to prove the generated code is structurally
 * sound (it compiles), not to run a suite that may need network or external services.
 * Ordinary compile failures come back as a {@code FAILED} {@link BuildReport}; only a
 * missing toolchain or a timeout raises {@link BuildExecutionException}.
 *
 * <p>Never exercised by the unit/integration suite — those inject a deterministic fake
 * {@link BuildExecutor}. This bean exists so a real deployment can verify generated code.
 */
@Slf4j
@Component
public class ProcessBuildExecutor implements BuildExecutor {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    @Value("${midas.build.timeout-seconds:180}")
    private long timeoutSeconds;

    @Override
    public BuildReport execute(Path projectDir, BuildTool tool) {
        List<String> command = commandFor(tool);
        log.info("[ProcessBuildExecutor] Running {} in {}", command, projectDir);

        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(projectDir.toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            // Toolchain not installed / not on PATH — environmental, not a code defect.
            throw new BuildExecutionException(
                    "Build toolchain for " + tool + " is unavailable: " + e.getMessage(), e);
        }

        String output = drain(process);
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new BuildExecutionException("Build was interrupted", e);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new BuildExecutionException(
                    "Build exceeded the " + timeoutSeconds + "s timeout and was terminated.");
        }

        int exit = process.exitValue();
        if (exit == 0) {
            return BuildReport.success(tool, tool + " build succeeded.");
        }
        return BuildReport.failure(
                tool, exit,
                BuildDiagnosticParser.parse(tool, output),
                tool + " build failed with exit code " + exit + ".",
                output);
    }

    private List<String> commandFor(BuildTool tool) {
        return switch (tool) {
            case MAVEN  -> List.of(exe("mvn"), "-B", "-q", "-e", "-DskipTests", "test-compile");
            case GRADLE -> List.of(exe("gradle"), "--quiet", "compileTestJava");
            case NPM    -> List.of(exe("npm"), "run", "build");
            case NONE   -> throw new BuildExecutionException("No build tool to execute for NONE.");
        };
    }

    /** Windows resolves {@code mvn}/{@code npm} via their {@code .cmd} shims. */
    private static String exe(String base) {
        return WINDOWS ? base + ".cmd" : base;
    }

    private String drain(Process process) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            log.warn("[ProcessBuildExecutor] Failed reading build output: {}", e.getMessage());
        }
        return sb.toString();
    }
}
