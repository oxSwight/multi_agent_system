package com.midas.d3.build;

/**
 * Which phase of a build a failure is attributed to.
 *
 * <p>Distinguishing these lets the self-healing loop and the quality harness tell "the generated
 * code won't compile" (a structural defect) from "it compiles but its tests don't pass" (a
 * behavioral defect) — two failures that warrant different remediation.
 */
public enum BuildPhase {

    /** Turning sources into artifacts (compile / type-check / bundle). */
    COMPILE,

    /** Running the compiled/generated automated test suite. */
    TEST
}
