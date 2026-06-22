package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.Set;

/**
 * Trims an architecture design artifact to the subset relevant for a single
 * HYBRID implementation pass, reducing context dilution for each LLM call.
 */
public final class ArchitectureSurfaceSlicer {

    private static final Set<String> CLIENT_COMPONENT_TYPES = Set.of(
            "UI", "CONTENT_SCRIPT", "BACKGROUND_WORKER", "MANIFEST", "STORAGE");
    private static final Set<String> SERVER_COMPONENT_TYPES = Set.of(
            "SERVICE", "CONTROLLER", "BACKEND");

    private ArchitectureSurfaceSlicer() {}

    public static JsonNode slice(JsonNode architecture, ImplementationSurface surface, ObjectMapper mapper) {
        if (architecture == null || !architecture.isObject()) {
            return architecture;
        }
        ObjectNode sliced = architecture.deepCopy();

        sliced.set("components", filterComponents(architecture.get("components"), surface, mapper));
        sliced.set("file_layout", filterFileLayout(architecture.get("file_layout"), surface, mapper));
        sliced.set("api_contracts", filterApiContracts(architecture.get("api_contracts"), surface, mapper));
        sliced.set("data_persistence", filterPersistence(architecture.get("data_persistence"), surface, mapper));

        return sliced;
    }

    public static boolean isClientPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        if (isServerPath(normalized)) {
            return false;
        }
        return normalized.equals("manifest.json")
                || normalized.endsWith("manifest.json")
                || normalized.endsWith(".ts")
                || normalized.endsWith(".tsx")
                || normalized.endsWith(".js")
                || normalized.endsWith(".jsx")
                || normalized.endsWith(".html")
                || normalized.endsWith(".css")
                || normalized.contains("content_script")
                || normalized.contains("popup")
                || normalized.startsWith("frontend/")
                || normalized.startsWith("public/")
                || normalized.startsWith("src/content")
                || normalized.startsWith("src/popup")
                || normalized.startsWith("src/ui")
                || normalized.startsWith("src/client");
    }

    public static boolean isServerPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        return normalized.startsWith("backend/")
                || normalized.contains("src/main/java")
                || normalized.endsWith(".java")
                || normalized.endsWith("pom.xml")
                || normalized.endsWith("build.gradle")
                || normalized.endsWith("build.gradle.kts")
                || normalized.endsWith("application.yml")
                || normalized.endsWith("application.yaml")
                || normalized.endsWith("application.properties")
                || normalized.startsWith("src/main/resources/");
    }

    private static ArrayNode filterComponents(JsonNode components, ImplementationSurface surface,
                                              ObjectMapper mapper) {
        ArrayNode filtered = mapper.createArrayNode();
        if (components == null || !components.isArray()) {
            return filtered;
        }
        Set<String> allowedTypes = surface == ImplementationSurface.CLIENT
                ? CLIENT_COMPONENT_TYPES
                : SERVER_COMPONENT_TYPES;

        for (JsonNode component : components) {
            if (!component.isObject()) {
                continue;
            }
            String type = component.path("type").asText("").toUpperCase(Locale.ROOT);
            if (allowedTypes.contains(type)) {
                filtered.add(component);
            }
        }
        return filtered;
    }

    private static ArrayNode filterFileLayout(JsonNode fileLayout, ImplementationSurface surface,
                                              ObjectMapper mapper) {
        ArrayNode filtered = mapper.createArrayNode();
        if (fileLayout == null || !fileLayout.isArray()) {
            return filtered;
        }
        for (JsonNode entry : fileLayout) {
            if (!entry.isTextual()) {
                continue;
            }
            String path = entry.asText();
            boolean include = surface == ImplementationSurface.CLIENT
                    ? isClientPath(path)
                    : isServerPath(path);
            if (include) {
                filtered.add(path);
            }
        }
        return filtered;
    }

    private static ArrayNode filterApiContracts(JsonNode apiContracts, ImplementationSurface surface,
                                                ObjectMapper mapper) {
        if (surface == ImplementationSurface.CLIENT) {
            return mapper.createArrayNode();
        }
        if (apiContracts == null || !apiContracts.isArray()) {
            return mapper.createArrayNode();
        }
        return apiContracts.deepCopy();
    }

    private static JsonNode filterPersistence(JsonNode persistence, ImplementationSurface surface,
                                              ObjectMapper mapper) {
        if (persistence == null || !persistence.isObject()) {
            return persistence;
        }
        String type = persistence.path("type").asText("").toUpperCase(Locale.ROOT);
        if (surface == ImplementationSurface.CLIENT) {
            if (Set.of("RELATIONAL", "EMBEDDED_DB", "CLOUD_DB").contains(type)) {
                ObjectNode none = mapper.createObjectNode();
                none.put("type", "NONE");
                none.set("schema", mapper.createArrayNode());
                return none;
            }
            return persistence.deepCopy();
        }
        if (Set.of("BROWSER_STORAGE", "NONE").contains(type) && !persistence.path("schema").isArray()) {
            return persistence.deepCopy();
        }
        return persistence.deepCopy();
    }
}
