package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.midas.d3.validation.ImplementationEngineerValidator;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    /**
     * Maps every generated source file to the feature(s) it plausibly implements, by <b>shared token
     * vocabulary</b> rather than the old requirement that a feature's full hyphenated slug appear
     * verbatim in the path. That old rule never held for camelCase/Java-Spring layouts
     * ({@code controller/TaskController.java} contains neither {@code task-crud} nor
     * {@code crud-operations-for-tasks}), so every file collapsed onto the first feature and the rest
     * were left citing the build descriptor ({@code pom.xml}) as their only "evidence" — which made the
     * product reviewer reject correct code as unimplemented. Three deliberate properties:
     * <ul>
     *   <li><b>Token matching</b> — a file matches a feature when their significant tokens overlap
     *       ({@code task} ↔ {@code TaskController}), comparing only the file name and its immediate
     *       directory so an app-named base package (e.g. {@code com/example/taskmanager}) cannot match
     *       every file.</li>
     *   <li><b>Multi-feature attribution</b> — one file can be evidence for several features (a single
     *       controller serving both {@code task-crud} and {@code task-filter}); the manifest schema
     *       imposes no file↔feature partition.</li>
     *   <li><b>Honest fallback</b> — build descriptors are never a feature's sole evidence, and a
     *       feature with no token match cites a real source file (never {@code pom.xml}).</li>
     * </ul>
     */
    private static Map<String, List<String>> assignFilesToFeatures(List<CoreFeature> features,
                                                                   List<String> allPaths) {
        List<Set<String>> featureTokens = new ArrayList<>(features.size());
        for (CoreFeature feature : features) {
            featureTokens.add(significantTokens(feature.id() + " " + feature.name()));
        }

        Map<String, List<String>> assignment = new LinkedHashMap<>();
        for (CoreFeature feature : features) {
            assignment.put(feature.id(), new ArrayList<>());
        }

        List<String> descriptors = new ArrayList<>();
        for (String path : allPaths) {
            if (isBuildDescriptor(path)) {
                descriptors.add(path);
                continue;
            }
            String matchText = featureMatchText(path);
            boolean matchedAny = false;
            for (int i = 0; i < features.size(); i++) {
                if (sharesToken(matchText, featureTokens.get(i))) {
                    assignment.get(features.get(i).id()).add(path);
                    matchedAny = true;
                }
            }
            if (!matchedAny) {
                // Shared scaffolding that names no feature (e.g. an Application bootstrap) is
                // attributed to the first feature so it is represented, mirroring the original
                // primary-feature fallback.
                assignment.get(features.get(0).id()).add(path);
            }
        }

        // Build descriptors (pom.xml, package.json, …) are project plumbing, not feature evidence:
        // park them under the first feature so they appear once and never stand in as another
        // feature's sole proof of implementation.
        if (!descriptors.isEmpty()) {
            assignment.get(features.get(0).id()).addAll(descriptors);
        }

        // A feature with no matched implementation must still cite real code, not a descriptor.
        List<String> codeFiles = allPaths.stream().filter(p -> !isBuildDescriptor(p)).toList();
        String evidenceFallback = codeFiles.isEmpty() ? allPaths.get(0) : codeFiles.get(0);
        for (CoreFeature feature : features) {
            if (assignment.get(feature.id()).isEmpty()) {
                assignment.get(feature.id()).add(evidenceFallback);
            }
        }
        return assignment;
    }

    /** Generic, non-discriminating words stripped from feature/path vocabulary before matching. */
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "for", "to", "by", "with", "in", "on", "at", "via",
            "from", "using", "use", "as", "into", "per",
            "feature", "features", "operation", "operations", "support", "system", "app", "application",
            "module", "modules", "general", "main", "core", "basic", "simple", "management",
            "functionality", "capability",
            "controller", "service", "repository", "model", "entity", "dao", "dto", "api", "rest",
            "crud", "data", "database", "db");

    /** Significant lowercase tokens of a label, plus a crude singular for each plural, minus stopwords. */
    private static Set<String> significantTokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (raw.length() < 2 || STOPWORDS.contains(raw)) {
                continue;
            }
            tokens.add(raw);
            if (raw.endsWith("s") && raw.length() > 3) {
                tokens.add(raw.substring(0, raw.length() - 1));
            }
        }
        return tokens;
    }

    /**
     * The discriminating slice of a path for feature matching: the file name (sans extension) plus its
     * immediate directory. Excludes the higher package path so an app-named base package — e.g.
     * {@code com/example/taskmanager} — cannot make the token {@code task} match every file.
     */
    private static String featureMatchText(String path) {
        String norm = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        int lastSlash = norm.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? norm.substring(lastSlash + 1) : norm;
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            fileName = fileName.substring(0, dot);
        }
        String parentDir = "";
        if (lastSlash >= 0) {
            int prevSlash = norm.lastIndexOf('/', lastSlash - 1);
            parentDir = norm.substring(prevSlash + 1, lastSlash);
        }
        return parentDir + " " + fileName;
    }

    private static boolean sharesToken(String matchText, Set<String> tokens) {
        for (String token : tokens) {
            if (matchText.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** True for project/build plumbing files that describe a project rather than implement a feature. */
    private static boolean isBuildDescriptor(String path) {
        String name = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return switch (name) {
            case "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
                 "package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "tsconfig.json",
                 "application.properties", "application.yml", "application.yaml", ".gitignore",
                 "dockerfile" -> true;
            default -> name.startsWith("readme") || name.endsWith(".lock") || name.endsWith(".md");
        };
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
