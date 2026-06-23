package com.midas.d3.build;

import java.nio.file.Path;

/**
 * Runs an actual build of a materialized project and reports the outcome.
 *
 * <p>This is the seam that makes the self-healing loop testable: the production
 * implementation ({@link ProcessBuildExecutor}) shells out to {@code mvn}/{@code npm},
 * while tests inject a deterministic fake. The state-machine and service logic depend
 * only on this interface, never on a live toolchain.
 */
public interface BuildExecutor {

    /**
     * Builds the project rooted at {@code projectDir} using {@code tool}.
     *
     * <p>Implementations must never throw for an ordinary build failure — a failed
     * compile is a normal, expected result and must come back as a {@code FAILED}
     * {@link BuildReport}. Throwing is reserved for genuinely exceptional conditions
     * (the toolchain is absent, the sandbox is unreadable, the process timed out).
     *
     * @param projectDir root of the materialized source tree; never null
     * @param tool       detected build tool; never {@link BuildTool#NONE}
     * @return the structured build outcome; never null
     */
    BuildReport execute(Path projectDir, BuildTool tool);
}
