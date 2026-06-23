package com.midas.d3.build;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Detects the build toolchain of a generated project from its source-file map
 * (a JSON object of {@code path → file contents}).
 *
 * <p>Detection is by build-descriptor filename, checked in priority order. The first
 * recognized descriptor wins. A project with no recognized descriptor resolves to
 * {@link BuildTool#NONE} and is skipped (not failed) by verification.
 */
public final class BuildToolDetector {

    private BuildToolDetector() {
    }

    public static BuildTool detect(JsonNode sourceMap) {
        if (sourceMap == null || !sourceMap.isObject() || sourceMap.isEmpty()) {
            return BuildTool.NONE;
        }

        boolean hasGradle = false;
        boolean hasNpm = false;

        for (Iterator<Map.Entry<String, JsonNode>> it = sourceMap.fields(); it.hasNext(); ) {
            String path = it.next().getKey().toLowerCase(Locale.ROOT);
            String name = fileName(path);

            // Maven wins outright — it is the project's own primary toolchain.
            if (name.equals("pom.xml")) {
                return BuildTool.MAVEN;
            }
            if (name.equals("build.gradle") || name.equals("build.gradle.kts")) {
                hasGradle = true;
            }
            if (name.equals("package.json")) {
                hasNpm = true;
            }
        }

        if (hasGradle) {
            return BuildTool.GRADLE;
        }
        if (hasNpm) {
            return BuildTool.NPM;
        }
        return BuildTool.NONE;
    }

    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
