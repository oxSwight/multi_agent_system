package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.midas.d3.validation.ImplementationEngineerValidator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministically builds {@code feature_manifest} from technical spec core_features and
 * generated source files — no LLM call required.
 */
public final class FeatureManifestBuilder {

    private FeatureManifestBuilder() {}

    public static JsonNode build(JsonNode technicalSpec,
                                 JsonNode sourceFiles,
                                 ObjectMapper mapper,
                                 boolean partialPass,
                                 ImplementationSurface surface) {
        List<String> allPaths = collectSourcePaths(sourceFiles);

        if (partialPass && surface != null) {
            String id = surface.name().toLowerCase(java.util.Locale.ROOT) + "-surface";
            String name = surface.name().charAt(0) + surface.name().substring(1).toLowerCase(java.util.Locale.ROOT)
                    + " surface";
            return singleEntry(mapper, id, name, allPaths);
        }

        List<CoreFeature> features = parseCoreFeatures(technicalSpec);

        if (features.isEmpty()) {
            return singleEntry(mapper, "main", "Main", allPaths);
        }

        if (features.size() == 1 || allPaths.isEmpty()) {
            CoreFeature feature = features.get(0);
            return singleEntry(mapper, feature.id(), feature.name(), allPaths);
        }

        Map<String, List<String>> filesByFeatureId = assignFilesToFeatures(features, allPaths);
        ArrayNode manifest = mapper.createArrayNode();

        for (CoreFeature feature : features) {
            List<String> files = filesByFeatureId.getOrDefault(feature.id(), List.of());
            if (files.isEmpty() && partialPass) {
                continue;
            }
            if (files.isEmpty()) {
                files = List.of(allPaths.get(0));
            }
            manifest.add(entry(mapper, feature.id(), feature.name(), files));
        }

        if (manifest.isEmpty()) {
            return singleEntry(mapper, features.get(0).id(), features.get(0).name(), allPaths);
        }

        return manifest;
    }

    private static List<String> collectSourcePaths(JsonNode sourceFiles) {
        List<String> paths = new ArrayList<>();
        if (sourceFiles == null || !sourceFiles.isObject()) {
            return paths;
        }
        Iterator<String> names = sourceFiles.fieldNames();
        while (names.hasNext()) {
            paths.add(names.next());
        }
        return paths;
    }

    private static Map<String, List<String>> assignFilesToFeatures(List<CoreFeature> features,
                                                                   List<String> allPaths) {
        Map<String, List<String>> assignment = new HashMap<>();
        Set<String> assigned = new HashSet<>();

        for (String path : allPaths) {
            String normalizedPath = path.toLowerCase(Locale.ROOT);
            CoreFeature best = null;
            for (CoreFeature feature : features) {
                if (pathMatchesFeature(normalizedPath, feature)) {
                    best = feature;
                    break;
                }
            }
            if (best == null) {
                best = features.get(0);
            }
            assignment.computeIfAbsent(best.id(), ignored -> new ArrayList<>()).add(path);
            assigned.add(path);
        }

        for (CoreFeature feature : features) {
            assignment.putIfAbsent(feature.id(), new ArrayList<>());
        }

        List<String> unassigned = allPaths.stream().filter(p -> !assigned.contains(p)).toList();
        if (!unassigned.isEmpty() && !features.isEmpty()) {
            assignment.get(features.get(0).id()).addAll(unassigned);
        }

        return assignment;
    }

    private static boolean pathMatchesFeature(String normalizedPath, CoreFeature feature) {
        String slug = feature.id();
        String nameSlug = ImplementationEngineerValidator.toFeatureId(feature.name());
        return normalizedPath.contains(slug) || normalizedPath.contains(nameSlug);
    }

    private static JsonNode singleEntry(ObjectMapper mapper, String id, String name, List<String> files) {
        ArrayNode manifest = mapper.createArrayNode();
        manifest.add(entry(mapper, id, name, files));
        return manifest;
    }

    private static ObjectNode entry(ObjectMapper mapper, String id, String name, List<String> files) {
        ObjectNode node = mapper.createObjectNode();
        node.put("feature_id", id);
        node.put("feature_name", name);

        ArrayNode filesArray = mapper.createArrayNode();
        if (files.isEmpty()) {
            filesArray.add(".");
        } else {
            files.forEach(filesArray::add);
        }
        node.set("files", filesArray);

        ArrayNode entryPoints = mapper.createArrayNode();
        String entryPoint = deriveEntryPoint(files.isEmpty() ? "." : files.get(0));
        entryPoints.add(entryPoint);
        node.set("entry_points", entryPoints);
        return node;
    }

    static String deriveEntryPoint(String path) {
        if (path == null || path.isBlank() || ".".equals(path)) {
            return "main";
        }
        String fileName = path;
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (slash >= 0 && slash < path.length() - 1) {
            fileName = path.substring(slash + 1);
        }
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            fileName = fileName.substring(0, dot);
        }
        return fileName.isBlank() ? "main" : fileName;
    }

    private static List<CoreFeature> parseCoreFeatures(JsonNode technicalSpec) {
        List<CoreFeature> features = new ArrayList<>();
        if (technicalSpec == null || !technicalSpec.isObject()) {
            return features;
        }
        JsonNode coreFeatures = technicalSpec.get("core_features");
        if (coreFeatures == null || !coreFeatures.isArray()) {
            return features;
        }
        for (JsonNode node : coreFeatures) {
            if (node.isTextual()) {
                String label = node.asText().strip();
                if (!label.isBlank()) {
                    features.add(new CoreFeature(
                            ImplementationEngineerValidator.toFeatureId(label), label));
                }
            } else if (node.isObject()) {
                String id = node.path("id").asText("").strip();
                String name = node.path("name").asText("").strip();
                if (id.isBlank() && name.isBlank()) {
                    continue;
                }
                if (id.isBlank()) {
                    id = ImplementationEngineerValidator.toFeatureId(name);
                }
                if (name.isBlank()) {
                    name = id;
                }
                features.add(new CoreFeature(id, name));
            }
        }
        return features;
    }

    private record CoreFeature(String id, String name) {}
}
