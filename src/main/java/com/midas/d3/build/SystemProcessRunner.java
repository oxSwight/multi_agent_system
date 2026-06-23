package com.midas.d3.build;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Production {@link ProcessRunner}: actually spawns the toolchain process, drains its combined
 * output, and enforces a timeout. Environmental failures (toolchain absent, timeout, interrupt)
 * surface as {@link BuildExecutionException}; a non-zero exit code is a normal, returned result.
 */
@Slf4j
@Component
public class SystemProcessRunner implements ProcessRunner {

    @Override
    public ProcessOutcome run(Path workingDir, List<String> command, long timeoutSeconds) {
        log.info("[SystemProcessRunner] Running {} in {}", command, workingDir);

        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            // Toolchain not installed / not on PATH — environmental, not a code defect.
            throw new BuildExecutionException(
                    "Build toolchain " + command + " is unavailable: " + e.getMessage(), e);
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
        return new ProcessOutcome(process.exitValue(), output);
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
            log.warn("[SystemProcessRunner] Failed reading build output: {}", e.getMessage());
        }
        return sb.toString();
    }
}
