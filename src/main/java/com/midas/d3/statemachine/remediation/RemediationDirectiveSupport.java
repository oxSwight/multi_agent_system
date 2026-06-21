package com.midas.d3.statemachine.remediation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RemediationDirectiveSupport {

    private RemediationDirectiveSupport() {}

    public static boolean isSurgicalPatch(JsonNode remediationDirective) {
        if (remediationDirective == null || remediationDirective.isNull() || remediationDirective.isMissingNode()) {
            return false;
        }
        return RemediationMode.SURGICAL_PATCH.name()
                .equals(remediationDirective.path("remediation_mode").asText("").strip());
    }

    public static List<String> affectedPaths(JsonNode remediationDirective) {
        if (remediationDirective == null || remediationDirective.isNull() || remediationDirective.isMissingNode()) {
            return List.of();
        }
        JsonNode pathsNode = remediationDirective.get("affected_paths");
        if (pathsNode == null || !pathsNode.isArray()) {
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (JsonNode entry : pathsNode) {
            if (entry.isTextual()) {
                String path = entry.asText().strip();
                if (!path.isBlank()) {
                    paths.add(path);
                }
            }
        }
        return Collections.unmodifiableList(paths);
    }
}
