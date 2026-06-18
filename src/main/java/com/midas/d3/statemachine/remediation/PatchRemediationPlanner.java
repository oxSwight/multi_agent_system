package com.midas.d3.statemachine.remediation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.midas.d3.context.MidasContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

@Component
public class PatchRemediationPlanner {

    private static final double LAYOUT_IMPACT_THRESHOLD = 0.5;
    private static final double CONTEXT_CAP_RATIO = 0.75;

    private final ObjectMapper objectMapper;
    private final int maxArtifactSizeKb;

    public PatchRemediationPlanner(
            ObjectMapper objectMapper,
            @Value("${midas.context.max-artifact-size-kb:512}") int maxArtifactSizeKb) {
        this.objectMapper = objectMapper;
        this.maxArtifactSizeKb = maxArtifactSizeKb;
    }

    public RemediationPlan plan(MidasContext context, JsonNode report) {
        ArrayNode gaps = filterCoverageGaps(report.path("coverage_matrix"));
        if (gaps.isEmpty()) {
            return RemediationPlan.fullRegen();
        }

        JsonNode manifest = context.getFeatureManifest();
        boolean manifestPresent = manifest != null && manifest.isArray() && !manifest.isEmpty();
        Set<String> pathCatalog = buildPathCatalog(context, manifest);
        LinkedHashSet<String> affectedPaths = new LinkedHashSet<>();
        LinkedHashSet<String> affectedFeatures = new LinkedHashSet<>();

        for (JsonNode gap : gaps) {
            String requestedFeature = gap.path("requested_feature").asText("").strip();
            String evidence = gap.path("evidence").asText("").strip();
            if (requestedFeature.isEmpty()) {
                continue;
            }
            affectedFeatures.add(resolveFeatureId(manifest, requestedFeature, evidence));

            LinkedHashSet<String> gapPaths = new LinkedHashSet<>();
            if (manifestPresent) {
                findManifestEntry(manifest, requestedFeature, evidence)
                        .ifPresent(entry -> collectManifestFiles(entry, gapPaths));
            }
            if (gapPaths.isEmpty()) {
                collectPathsFromEvidence(evidence, pathCatalog, gapPaths);
            }
            affectedPaths.addAll(gapPaths);
        }

        if (affectedPaths.isEmpty()) {
            return RemediationPlan.fullRegen();
        }

        if (exceedsLayoutThreshold(affectedPaths, context.getArchitectureDesign())) {
            return RemediationPlan.fullRegen();
        }
        if (exceedsContextCap(affectedPaths, context)) {
            return RemediationPlan.fullRegen();
        }

        return new RemediationPlan(
                RemediationMode.SURGICAL_PATCH,
                List.copyOf(new ArrayList<>(affectedPaths)),
                List.copyOf(new ArrayList<>(affectedFeatures)));
    }

    private ArrayNode filterCoverageGaps(JsonNode coverageMatrix) {
        ArrayNode gaps = objectMapper.createArrayNode();
        if (!coverageMatrix.isArray()) {
            return gaps;
        }
        for (JsonNode row : coverageMatrix) {
            String status = row.path("status").asText("").strip().toUpperCase(Locale.ROOT);
            if ("MISSING".equals(status) || "PARTIAL".equals(status)) {
                gaps.add(row.deepCopy());
            }
        }
        return gaps;
    }

    private Set<String> buildPathCatalog(MidasContext context, JsonNode manifest) {
        TreeSet<String> catalog = new TreeSet<>();
        collectLayoutPaths(context.getArchitectureDesign(), catalog);
        collectSourcePaths(context.getGeneratedSourceCode(), catalog);
        if (manifest != null && manifest.isArray()) {
            for (JsonNode entry : manifest) {
                collectManifestFiles(entry, catalog);
            }
        }
        return catalog;
    }

    private void collectLayoutPaths(JsonNode architecture, Set<String> out) {
        JsonNode layout = architecture == null ? null : architecture.path("file_layout");
        if (layout == null || !layout.isArray()) {
            return;
        }
        for (JsonNode pathNode : layout) {
            if (pathNode.isTextual() && !pathNode.asText().isBlank()) {
                out.add(pathNode.asText().strip());
            }
        }
    }

    private void collectSourcePaths(JsonNode sourceMap, Set<String> out) {
        if (sourceMap == null || !sourceMap.isObject()) {
            return;
        }
        sourceMap.fieldNames().forEachRemaining(name -> {
            if (!name.isBlank()) {
                out.add(name.strip());
            }
        });
    }

    private void collectManifestFiles(JsonNode entry, Set<String> out) {
        JsonNode files = entry.path("files");
        if (!files.isArray()) {
            return;
        }
        for (JsonNode fileNode : files) {
            if (fileNode.isTextual() && !fileNode.asText().isBlank()) {
                out.add(fileNode.asText().strip());
            }
        }
    }

    private Optional<JsonNode> findManifestEntry(JsonNode manifest, String requestedFeature, String evidence) {
        String normalizedRequest = requestedFeature.strip().toLowerCase(Locale.ROOT);
        String slugRequest = toFeatureSlug(requestedFeature);
        String evidenceLower = evidence.toLowerCase(Locale.ROOT);

        for (JsonNode entry : manifest) {
            if (!entry.isObject()) {
                continue;
            }
            String featureId = entry.path("feature_id").asText("").strip().toLowerCase(Locale.ROOT);
            String featureName = entry.path("feature_name").asText("").strip().toLowerCase(Locale.ROOT);
            if (featureId.equals(slugRequest)
                    || featureName.equals(normalizedRequest)
                    || (!featureId.isEmpty() && evidenceLower.contains(featureId))) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    private String resolveFeatureId(JsonNode manifest, String requestedFeature, String evidence) {
        if (manifest != null && manifest.isArray() && !manifest.isEmpty()) {
            Optional<JsonNode> entry = findManifestEntry(manifest, requestedFeature, evidence);
            if (entry.isPresent()) {
                String featureId = entry.get().path("feature_id").asText("").strip();
                if (!featureId.isEmpty()) {
                    return featureId;
                }
            }
        }
        return toFeatureSlug(requestedFeature);
    }

    private String toFeatureSlug(String feature) {
        return feature.strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private void collectPathsFromEvidence(String evidence, Set<String> catalog, Set<String> out) {
        if (evidence.isBlank() || catalog.isEmpty()) {
            return;
        }
        String evidenceLower = evidence.toLowerCase(Locale.ROOT);
        for (String path : catalog) {
            if (evidenceLower.contains(path.toLowerCase(Locale.ROOT))) {
                out.add(path);
            }
        }
    }

    private boolean exceedsLayoutThreshold(Set<String> affectedPaths, JsonNode architecture) {
        JsonNode layout = architecture == null ? null : architecture.path("file_layout");
        if (layout == null || !layout.isArray() || layout.isEmpty()) {
            return false;
        }
        int total = 0;
        int affected = 0;
        for (JsonNode pathNode : layout) {
            if (!pathNode.isTextual() || pathNode.asText().isBlank()) {
                continue;
            }
            total++;
            if (affectedPaths.contains(pathNode.asText().strip())) {
                affected++;
            }
        }
        if (total <= 1) {
            return false;
        }
        return (double) affected / total > LAYOUT_IMPACT_THRESHOLD;
    }

    private boolean exceedsContextCap(Set<String> affectedPaths, MidasContext context) {
        long capBytes = (long) (maxArtifactSizeKb * 1024L * CONTEXT_CAP_RATIO);
        long total = estimateSourceBytes(affectedPaths, context.getGeneratedSourceCode())
                + estimateRelatedTestBytes(affectedPaths, context.getGeneratedTests());
        return total > capBytes;
    }

    private long estimateSourceBytes(Set<String> affectedPaths, JsonNode sourceMap) {
        if (sourceMap == null || !sourceMap.isObject()) {
            return 0L;
        }
        long total = 0L;
        for (String path : affectedPaths) {
            JsonNode content = sourceMap.get(path);
            if (content != null && content.isTextual()) {
                total += content.asText().getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return total;
    }

    private long estimateRelatedTestBytes(Set<String> affectedPaths, JsonNode testMap) {
        if (testMap == null || !testMap.isObject()) {
            return 0L;
        }
        long total = 0L;
        var fields = testMap.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String testPath = entry.getKey();
            for (String sourcePath : affectedPaths) {
                if (testRelatesToAffectedSource(testPath, sourcePath)) {
                    JsonNode content = entry.getValue();
                    if (content != null && content.isTextual()) {
                        total += content.asText().getBytes(StandardCharsets.UTF_8).length;
                    }
                    break;
                }
            }
        }
        return total;
    }

    private boolean testRelatesToAffectedSource(String testPath, String sourcePath) {
        int slash = sourcePath.lastIndexOf('/');
        String sourceFile = slash >= 0 ? sourcePath.substring(slash + 1) : sourcePath;
        int dot = sourceFile.lastIndexOf('.');
        String baseName = dot > 0 ? sourceFile.substring(0, dot) : sourceFile;
        return testPath.contains(baseName);
    }
}
