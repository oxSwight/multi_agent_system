package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic reference-consistency check for a browser-extension {@code manifest.json}: every
 * <b>code</b> file the manifest points at (service worker, content scripts, popup/options HTML,
 * declared resources) MUST exist in the generated {@code source_files} map, resolved relative to
 * the manifest's own directory (the extension load-root).
 *
 * <h2>Why this exists (compiler, not oracle)</h2>
 * The Chrome-extension that passed the gate but would not load did so because the manifest
 * referenced files the model never wrote — a broken cross-file link the schema validators could
 * not see. This closes that gap deterministically, at zero token cost, independent of the LLM.
 *
 * <h2>Code references vs binary assets</h2>
 * Only references the LLM <em>can</em> author are gated here (scripts, stylesheets, HTML pages).
 * Binary asset references (icons / images) are intentionally NOT failed: the model cannot emit a
 * PNG, so a missing icon is the deterministic Assembler's job to backfill with a placeholder, not
 * a code-generation violation that would loop the model forever trying to "write" a binary.
 */
public final class ManifestReferenceValidator {

    private ManifestReferenceValidator() {}

    /**
     * Reusable core: returns every manifest code-reference problem found in {@code sourceFiles}
     * (an unparseable manifest, or a code file the manifest references that is absent). Shared by the
     * P0 code-generation gate and the P1 build-surface verifier so both apply the identical rule.
     */
    public static List<String> findMissingCodeReferences(JsonNode sourceFiles, ObjectMapper mapper) {
        List<String> violations = new java.util.ArrayList<>();
        validateSourceFiles(sourceFiles, mapper, violations);
        return violations;
    }

    static void validateSourceFiles(JsonNode sourceFiles, ObjectMapper mapper, List<String> violations) {
        if (sourceFiles == null || !sourceFiles.isObject()) {
            return;
        }

        Set<String> sourcePaths = new HashSet<>();
        Iterator<Map.Entry<String, JsonNode>> fields = sourceFiles.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getValue().isTextual()) {
                sourcePaths.add(WebResourcePaths.normalize(entry.getKey()));
            }
        }

        fields = sourceFiles.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String path = WebResourcePaths.normalize(entry.getKey());
            if (!entry.getValue().isTextual() || !isManifestPath(path)) {
                continue;
            }
            JsonNode manifest;
            try {
                manifest = mapper.readTree(entry.getValue().asText());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                violations.add("[" + path + "] is not valid JSON — a browser-extension manifest "
                        + "must parse: " + e.getOriginalMessage());
                continue;
            }
            if (manifest == null || !manifest.isObject() || !looksLikeExtensionManifest(manifest)) {
                continue;
            }
            String manifestDir = WebResourcePaths.directoryOf(path);
            for (CodeReference ref : collectCodeReferences(manifest)) {
                if (WebResourcePaths.isExternal(ref.ref())) {
                    continue;
                }
                String resolved = WebResourcePaths.resolveRelative(manifestDir, ref.ref());
                if (!sourcePaths.contains(resolved)) {
                    violations.add("[" + path + "] " + ref.kind() + " references missing file ['"
                            + ref.ref() + "'] (resolved: [" + resolved + "]) — every manifest "
                            + "code reference must point to a generated source file. Generate the "
                            + "missing file or correct the reference path.");
                }
            }
        }
    }

    private static boolean isManifestPath(String normalizedPath) {
        return normalizedPath.equals("manifest.json") || normalizedPath.endsWith("/manifest.json");
    }

    /**
     * Distinguishes a web-extension manifest from an unrelated {@code manifest.json} (e.g. a PWA web
     * app manifest) so the extension-specific reference rules are not applied to the wrong file.
     */
    private static boolean looksLikeExtensionManifest(JsonNode manifest) {
        return manifest.has("manifest_version")
                || manifest.has("background")
                || manifest.has("content_scripts")
                || manifest.has("action")
                || manifest.has("browser_action");
    }

    private static List<CodeReference> collectCodeReferences(JsonNode manifest) {
        List<CodeReference> refs = new ArrayList<>();

        JsonNode background = manifest.get("background");
        if (background != null && background.isObject()) {
            addString(refs, "background.service_worker", background.get("service_worker"));
            addString(refs, "background.page", background.get("page"));
            addArray(refs, "background.scripts", background.get("scripts"));
        }

        JsonNode contentScripts = manifest.get("content_scripts");
        if (contentScripts != null && contentScripts.isArray()) {
            for (JsonNode cs : contentScripts) {
                if (!cs.isObject()) {
                    continue;
                }
                addArray(refs, "content_scripts.js", cs.get("js"));
                addArray(refs, "content_scripts.css", cs.get("css"));
            }
        }

        addPopup(refs, manifest.get("action"), "action.default_popup");
        addPopup(refs, manifest.get("browser_action"), "browser_action.default_popup");
        addPopup(refs, manifest.get("page_action"), "page_action.default_popup");

        addString(refs, "options_page", manifest.get("options_page"));
        JsonNode optionsUi = manifest.get("options_ui");
        if (optionsUi != null && optionsUi.isObject()) {
            addString(refs, "options_ui.page", optionsUi.get("page"));
        }

        JsonNode overrides = manifest.get("chrome_url_overrides");
        if (overrides != null && overrides.isObject()) {
            overrides.fields().forEachRemaining(e ->
                    addString(refs, "chrome_url_overrides." + e.getKey(), e.getValue()));
        }

        JsonNode sandbox = manifest.get("sandbox");
        if (sandbox != null && sandbox.isObject()) {
            addArray(refs, "sandbox.pages", sandbox.get("pages"));
        }

        collectWebAccessibleResources(refs, manifest.get("web_accessible_resources"));
        return refs;
    }

    private static void addPopup(List<CodeReference> refs, JsonNode actionNode, String kind) {
        if (actionNode != null && actionNode.isObject()) {
            addString(refs, kind, actionNode.get("default_popup"));
        }
    }

    /**
     * web_accessible_resources may be MV3 (array of {@code {resources:[...], matches:[...]}}) or the
     * legacy MV2 array of strings. Only code-like resources (.js/.css/.html) without glob wildcards
     * are gated; image/asset entries and globs are left for the Assembler / runtime.
     */
    private static void collectWebAccessibleResources(List<CodeReference> refs, JsonNode war) {
        if (war == null || !war.isArray()) {
            return;
        }
        for (JsonNode item : war) {
            if (item.isTextual()) {
                addCodeResource(refs, item.asText());
            } else if (item.isObject()) {
                JsonNode resources = item.get("resources");
                if (resources != null && resources.isArray()) {
                    for (JsonNode r : resources) {
                        if (r.isTextual()) {
                            addCodeResource(refs, r.asText());
                        }
                    }
                }
            }
        }
    }

    private static void addCodeResource(List<CodeReference> refs, String resource) {
        if (resource == null || resource.contains("*")) {
            return;
        }
        if (isCodeFile(resource)) {
            refs.add(new CodeReference("web_accessible_resources", resource.strip()));
        }
    }

    private static void addString(List<CodeReference> refs, String kind, JsonNode node) {
        if (node != null && node.isTextual() && !node.asText().isBlank()) {
            refs.add(new CodeReference(kind, node.asText().strip()));
        }
    }

    private static void addArray(List<CodeReference> refs, String kind, JsonNode node) {
        if (node == null || !node.isArray()) {
            return;
        }
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                refs.add(new CodeReference(kind, item.asText().strip()));
            }
        }
    }

    private static boolean isCodeFile(String ref) {
        String lower = WebResourcePaths.normalize(ref).toLowerCase(Locale.ROOT);
        return lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".css")
                || lower.endsWith(".html") || lower.endsWith(".htm");
    }

    private record CodeReference(String kind, String ref) {}
}
