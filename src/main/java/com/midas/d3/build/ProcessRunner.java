package com.midas.d3.build;

import java.nio.file.Path;
import java.util.List;

/**
 * Runs a single external command inside a project directory and returns its exit code and combined
 * output. Extracted from {@link ProcessBuildExecutor} so the executor's multi-phase build logic can
 * be unit-tested with a deterministic fake instead of a live toolchain.
 */
@FunctionalInterface
public interface ProcessRunner {

    /**
     * @param workingDir     directory to run the command in
     * @param command        the command and its arguments
     * @param timeoutSeconds hard timeout before the process is killed
     * @return the command's exit code and combined stdout/stderr
     * @throws BuildExecutionException for environmental failures (toolchain absent, timeout, interrupt)
     */
    ProcessOutcome run(Path workingDir, List<String> command, long timeoutSeconds);

    /** Exit code + combined stdout/stderr of one command invocation. */
    record ProcessOutcome(int exitCode, String output) {
        public ProcessOutcome {
            output = (output == null) ? "" : output;
        }
    }
}
