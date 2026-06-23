package com.midas.d3.build;

/**
 * The build toolchain detected for a generated project.
 *
 * <p>{@link #NONE} means no recognized build descriptor was found in the generated
 * source map — verification cannot run and is reported as a non-blocking skip rather
 * than a failure (we never fail a project for being, say, a static HTML bundle).
 */
public enum BuildTool {
    MAVEN,
    GRADLE,
    NPM,
    NONE
}
