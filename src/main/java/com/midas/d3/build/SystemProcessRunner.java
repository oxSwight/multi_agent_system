package com.midas.d3.build;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Production {@link ProcessRunner}: actually spawns the toolchain process and contains it. Because
 * MIDAS now executes <em>LLM-generated tests</em> (not just compiles them), this boundary is
 * security-critical, so it enforces three hard limits:
 *
 * <ul>
 *   <li><b>Strict timeout with a process-tree kill.</b> {@code waitFor(timeout)} bounds wall-clock;
 *       on expiry the whole descendant tree is force-killed (not just the direct child), so a
 *       runaway {@code java}/{@code node} grandchild — e.g. an infinite loop in a generated test —
 *       cannot survive as an orphan.</li>
 *   <li><b>Output drained on a background thread.</b> The previous drain-then-wait order meant a
 *       process printing without bound never returned control to the timeout — it could hang the
 *       caller forever. Draining off-thread keeps the timeout authoritative.</li>
 *   <li><b>Capped output buffer.</b> Output is retained up to {@link #MAX_OUTPUT_CHARS}; beyond that
 *       it is read-and-discarded (so the child never blocks on a full pipe) but never accumulated,
 *       bounding heap against an output-flood DoS.</li>
 * </ul>
 *
 * Environmental failures (toolchain absent, timeout, interrupt) surface as
 * {@link BuildExecutionException}; a non-zero exit code is a normal, returned result.
 */
@Slf4j
@Component
public class SystemProcessRunner implements ProcessRunner {

    /** Hard cap on retained combined output — bounds heap against a runaway/flooding process. */
    static final int MAX_OUTPUT_CHARS = 1_000_000;

    /** Grace period to let a force-killed process tree actually die before we give up. */
    private static final long KILL_GRACE_SECONDS = 5;

    @Override
    public ProcessOutcome run(Path workingDir, List<String> command, long timeoutSeconds) {
        log.info("[SystemProcessRunner] Running {} in {} (timeout {}s)", command, workingDir, timeoutSeconds);

        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(workingDir.toFile())   // confine the working directory to the sandbox
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            // Toolchain not installed / not on PATH — environmental, not a code defect.
            throw new BuildExecutionException(
                    "Build toolchain " + command + " is unavailable: " + e.getMessage(), e);
        }

        // Drain OFF-THREAD so an unbounded printer can never starve the timeout below.
        StringBuffer output = new StringBuffer();
        Thread drainer = new Thread(() -> drainCapped(process.getInputStream(), output), "sandbox-output-drain");
        drainer.setDaemon(true);
        drainer.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process);
            throw new BuildExecutionException("Build was interrupted", e);
        }

        if (!finished) {
            killTree(process);
            joinQuietly(drainer);
            throw new BuildExecutionException(
                    "Build exceeded the " + timeoutSeconds + "s timeout and its process tree was force-killed.");
        }

        joinQuietly(drainer);
        return new ProcessOutcome(process.exitValue(), output.toString());
    }

    /**
     * Reads {@code in} to EOF, retaining at most {@link #MAX_OUTPUT_CHARS} characters. Once the cap
     * is hit it keeps reading (so the child process never blocks on a full stdout pipe) but discards,
     * so heap stays bounded no matter how much the process emits. Package-private for unit testing.
     */
    static void drainCapped(InputStream in, StringBuffer out) {
        char[] chunk = new char[8192];
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            int n;
            while ((n = reader.read(chunk)) != -1) {
                int len = out.length();
                if (len >= MAX_OUTPUT_CHARS) {
                    continue; // pipe kept drained, content discarded
                }
                out.append(chunk, 0, Math.min(n, MAX_OUTPUT_CHARS - len));
                if (out.length() >= MAX_OUTPUT_CHARS) {
                    out.append("\n[...output truncated for sandbox containment]");
                }
            }
        } catch (IOException e) {
            // Stream closed (commonly because the process was force-killed) — expected; stop draining.
            log.debug("[SystemProcessRunner] Output drain ended: {}", e.getMessage());
        }
    }

    /** Force-kills the process and every descendant so no runaway child survives the timeout. */
    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(KILL_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(KILL_GRACE_SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
