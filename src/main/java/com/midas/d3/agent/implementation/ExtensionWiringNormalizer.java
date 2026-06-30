package com.midas.d3.agent.implementation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministically repairs <b>cross-file reference wiring</b> in a generated browser-extension source
 * map — both popup HTML {@code <script src>} references and {@code manifest.json} code references — so
 * a mechanically-fixable inconsistency can no longer dead-end CODE_GENERATION.
 *
 * <h2>Why this exists (determinism over tokens)</h2>
 * Live runs showed even a strong model satisfy every functional criterion yet trip the structural
 * reference gates: a popup HTML references {@code sidebar.js} while the module sits at
 * {@code src/sidebar.js}; a manifest declares {@code "service_worker": "background.js"} while the file
 * is a directory away. These are purely mechanical — exactly one correct fix, no semantics to decide —
 * so resolving them in Java is free and reliable, where a self-healing LLM round-trip is neither. This
 * runs <em>before</em> the assembled-envelope gate and makes the artifact satisfy the existing P0
 * reference rules ({@code FrontendIntegrationValidator}, {@code ManifestReferenceValidator}) rather
 * than weakening them.
 *
 * <h2>What it does (only safe fixes)</h2>
 * <ol>
 *   <li><b>Rewrite</b> a popup {@code <script src>} or a manifest code reference whose path does not
 *       resolve to a generated file when exactly one generated file shares its basename, repointing it
 *       at that file's real relative path. Ambiguous (0 or &gt;1 match) references are left alone.</li>
 *   <li><b>Inject</b> a {@code <script src>} for each same-directory popup-companion module (a
 *       non-worker {@code .js} beside the popup HTML) that no script tag references.</li>
 *   <li><b>Prune</b> a manifest code reference to a file that was never generated and has no basename
 *       match — a hallucinated reference (e.g. a {@code background.service_worker} the architect never
 *       planned) that would otherwise dead-end the build. The authoritative design (which omitted the
 *       file) wins; an MV3 {@code background} block left with no entry point is dropped wholesale.</li>
 * </ol>
 * It never invents a missing file and never touches icon/image references (those are the Assembler's
 * job), and returns the input unchanged when nothing needs fixing.
 */
public final class ExtensionWiringNormalizer {

    private static final Pattern SCRIPT_SRC = Pattern.compile(
            "<script[^>]*\\bsrc\\s*=\\s*([\"'])(.*?)\\1[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BODY_CLOSE = Pattern.compile("</body>", Pattern.CASE_INSENSITIVE);

    private ExtensionWiringNormalizer() {
    }

    /**
     * @return a source map with extension reference wiring repaired, or {@code sourceFiles} unchanged
     * when no safe fix applies.
     */
    public static JsonNode normalize(JsonNode sourceFiles, ObjectMapper mapper) {
        if (sourceFiles == null || !sourceFiles.isObject() || sourceFiles.isEmpty()) {
            return sourceFiles;
        }

        Map<String, String> pathsByBasename = indexByBasename(sourceFiles);
        Set<String> allPaths = new LinkedHashSet<>();
        boolean usesChromeStorage = false;
        for (Map.Entry<String, JsonNode> e : fields(sourceFiles)) {
            if (e.getValue().isTextual()) {
                allPaths.add(normalize(e.getKey()));
                if (e.getValue().asText().contains("chrome.storage")) {
                    usesChromeStorage = true;
                }
            }
        }

        Map<String, String> rewritten = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : fields(sourceFiles)) {
            String path = entry.getKey();
            JsonNode value = entry.getValue();
            if (!value.isTextual()) {
                continue;
            }
            if (isPopupHtml(path)) {
                String fixed = repairHtml(normalize(path), value.asText(), sourceFiles, pathsByBasename, allPaths);
                if (!fixed.equals(value.asText())) {
                    rewritten.put(path, fixed);
                }
            } else if (isManifest(path)) {
                String fixed = repairManifest(normalize(path), value.asText(), mapper,
                        pathsByBasename, allPaths, usesChromeStorage);
                if (fixed != null && !fixed.equals(value.asText())) {
                    rewritten.put(path, fixed);
                }
            }
        }

        if (rewritten.isEmpty()) {
            return sourceFiles;
        }
        ObjectNode copy = sourceFiles.deepCopy();
        rewritten.forEach(copy::put);
        return copy;
    }

    // ── Popup HTML <script src> wiring ───────────────────────────────────────────

    private static String repairHtml(String htmlPath, String html, JsonNode sourceFiles,
                                     Map<String, String> pathsByBasename, Set<String> allPaths) {
        String htmlDir = directoryOf(htmlPath);

        Matcher m = SCRIPT_SRC.matcher(html);
        StringBuilder sb = new StringBuilder();
        Set<String> referencedResolved = new LinkedHashSet<>();
        while (m.find()) {
            String quote = m.group(1);
            String ref = m.group(2).strip();
            String replacementTag = m.group();
            if (!isExternal(ref)) {
                String resolved = resolveRelative(htmlDir, ref);
                if (!allPaths.contains(resolved)) {
                    String target = pathsByBasename.get(basename(normalize(ref)));
                    if (target != null) {
                        String rel = relativePath(htmlDir, target);
                        replacementTag = m.group().replace(quote + ref + quote, quote + rel + quote);
                        resolved = target;
                    }
                }
                referencedResolved.add(resolved);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacementTag));
        }
        m.appendTail(sb);
        String result = sb.toString();

        List<String> toInject = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : fields(sourceFiles)) {
            if (!entry.getValue().isTextual()) {
                continue;
            }
            String candidate = normalize(entry.getKey());
            if (isInjectableCompanion(candidate, htmlDir) && !referencedResolved.contains(candidate)) {
                toInject.add(relativePath(htmlDir, candidate));
            }
        }
        return toInject.isEmpty() ? result : injectScripts(result, toInject);
    }

    private static String injectScripts(String html, List<String> relPaths) {
        StringBuilder tags = new StringBuilder();
        for (String rel : relPaths) {
            tags.append("<script src=\"").append(rel).append("\"></script>\n");
        }
        Matcher body = BODY_CLOSE.matcher(html);
        if (body.find()) {
            return html.substring(0, body.start()) + tags + html.substring(body.start());
        }
        return html + "\n" + tags;
    }

    // ── manifest.json code-reference wiring ──────────────────────────────────────

    private static String repairManifest(String manifestPath, String content, ObjectMapper mapper,
                                         Map<String, String> pathsByBasename, Set<String> allPaths,
                                         boolean usesChromeStorage) {
        JsonNode parsed;
        try {
            parsed = mapper.readTree(content);
        } catch (JsonProcessingException e) {
            return null; // not parseable — leave it for the gate to report as-is
        }
        if (parsed == null || !parsed.isObject()) {
            return null;
        }
        ObjectNode manifest = (ObjectNode) parsed;
        String dir = directoryOf(manifestPath);
        boolean[] changed = {false};

        // Least-privilege: a declared "storage" permission that no generated JS actually uses is a
        // dead declaration the P0 gate rejects. Removing it (rather than inventing storage code) is the
        // safe deterministic resolution consistent with what the code actually does.
        if (!usesChromeStorage) {
            removePermission(manifest, "permissions", "storage", changed);
            removePermission(manifest, "optional_permissions", "storage", changed);
        }

        if (manifest.get("background") instanceof ObjectNode bg) {
            fixStringRef(bg, "service_worker", dir, pathsByBasename, allPaths, changed);
            fixStringRef(bg, "page", dir, pathsByBasename, allPaths, changed);
            fixArrayRefs(bg, "scripts", dir, mapper, pathsByBasename, allPaths, changed);
            // An MV3 background with no resolvable entry point is invalid — drop the whole block
            // (the design that omitted a worker is authoritative over a hallucinated reference).
            if (!bg.has("service_worker") && !bg.has("page") && !bg.has("scripts")) {
                manifest.remove("background");
            }
        }

        if (manifest.get("content_scripts") instanceof ArrayNode csArray) {
            for (int i = csArray.size() - 1; i >= 0; i--) {
                if (csArray.get(i) instanceof ObjectNode cs) {
                    fixArrayRefs(cs, "js", dir, mapper, pathsByBasename, allPaths, changed);
                    fixArrayRefs(cs, "css", dir, mapper, pathsByBasename, allPaths, changed);
                    if (!cs.has("js") && !cs.has("css")) {
                        csArray.remove(i);
                        changed[0] = true;
                    }
                }
            }
            if (csArray.isEmpty()) {
                manifest.remove("content_scripts");
            }
        }

        fixPopupRef(manifest.get("action"), dir, pathsByBasename, allPaths, changed);
        fixPopupRef(manifest.get("browser_action"), dir, pathsByBasename, allPaths, changed);
        fixPopupRef(manifest.get("page_action"), dir, pathsByBasename, allPaths, changed);
        fixStringRef(manifest, "options_page", dir, pathsByBasename, allPaths, changed);
        if (manifest.get("options_ui") instanceof ObjectNode optionsUi) {
            fixStringRef(optionsUi, "page", dir, pathsByBasename, allPaths, changed);
        }

        if (!changed[0]) {
            return null;
        }
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static void fixPopupRef(JsonNode actionNode, String dir,
                                    Map<String, String> pathsByBasename, Set<String> allPaths, boolean[] changed) {
        if (actionNode instanceof ObjectNode action) {
            fixStringRef(action, "default_popup", dir, pathsByBasename, allPaths, changed);
        }
    }

    /** Resolves a single manifest string reference: keep when valid, rewrite to a unique basename match, else prune. */
    private static void fixStringRef(ObjectNode parent, String field, String dir,
                                     Map<String, String> pathsByBasename, Set<String> allPaths, boolean[] changed) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isTextual()) {
            return;
        }
        String ref = node.asText().strip();
        if (isExternal(ref) || allPaths.contains(resolveRelative(dir, ref))) {
            return; // already valid
        }
        String target = pathsByBasename.get(basename(normalize(ref)));
        if (target != null) {
            parent.put(field, relativePath(dir, target));
        } else {
            parent.remove(field); // unbacked reference to a file that was never generated
        }
        changed[0] = true;
    }

    /** Resolves a manifest array of references in place, rewriting unique matches and pruning unbacked ones. */
    private static void fixArrayRefs(ObjectNode parent, String field, String dir, ObjectMapper mapper,
                                     Map<String, String> pathsByBasename, Set<String> allPaths, boolean[] changed) {
        if (!(parent.get(field) instanceof ArrayNode array)) {
            return;
        }
        ArrayNode kept = mapper.createArrayNode();
        boolean localChange = false;
        for (JsonNode item : array) {
            if (!item.isTextual()) {
                kept.add(item);
                continue;
            }
            String ref = item.asText().strip();
            if (isExternal(ref) || allPaths.contains(resolveRelative(dir, ref))) {
                kept.add(ref);
                continue;
            }
            String target = pathsByBasename.get(basename(normalize(ref)));
            if (target != null) {
                kept.add(relativePath(dir, target));
            }
            localChange = true; // rewritten or pruned
        }
        if (!localChange) {
            return;
        }
        if (kept.isEmpty()) {
            parent.remove(field);
        } else {
            parent.set(field, kept);
        }
        changed[0] = true;
    }

    /** Removes a named permission from a manifest permissions array (and the array if it empties). */
    private static void removePermission(ObjectNode manifest, String field, String permission, boolean[] changed) {
        if (!(manifest.get(field) instanceof ArrayNode perms)) {
            return;
        }
        ArrayNode kept = perms.arrayNode();
        boolean removed = false;
        for (JsonNode item : perms) {
            if (item.isTextual() && item.asText().strip().equalsIgnoreCase(permission)) {
                removed = true;
            } else {
                kept.add(item);
            }
        }
        if (!removed) {
            return;
        }
        if (kept.isEmpty()) {
            manifest.remove(field);
        } else {
            manifest.set(field, kept);
        }
        changed[0] = true;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static Map<String, String> indexByBasename(JsonNode sourceFiles) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : fields(sourceFiles)) {
            if (!entry.getValue().isTextual()) {
                continue;
            }
            String path = normalize(entry.getKey());
            String lower = path.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".js") || lower.endsWith(".css") || lower.endsWith(".html")) {
                grouped.computeIfAbsent(basename(path), k -> new ArrayList<>()).add(path);
            }
        }
        Map<String, String> unique = new LinkedHashMap<>();
        grouped.forEach((base, paths) -> {
            if (paths.size() == 1) {
                unique.put(base, paths.get(0));
            }
        });
        return unique;
    }

    private static boolean isPopupHtml(String path) {
        String lower = normalize(path).toLowerCase(Locale.ROOT);
        return lower.endsWith(".html") && lower.contains("popup");
    }

    private static boolean isManifest(String path) {
        String lower = normalize(path).toLowerCase(Locale.ROOT);
        return lower.equals("manifest.json") || lower.endsWith("/manifest.json");
    }

    private static boolean isInjectableCompanion(String jsPath, String htmlDir) {
        String lower = jsPath.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".js")) {
            return false;
        }
        if (lower.contains("background") || lower.contains("content_script")
                || lower.contains("service_worker") || lower.endsWith(".test.js") || lower.endsWith(".spec.js")) {
            return false;
        }
        return directoryOf(jsPath).equals(htmlDir);
    }

    private static boolean isExternal(String ref) {
        return ref.isEmpty() || ref.startsWith("http://") || ref.startsWith("https://")
                || ref.startsWith("//") || ref.contains("://");
    }

    private static List<Map.Entry<String, JsonNode>> fields(JsonNode node) {
        List<Map.Entry<String, JsonNode>> out = new ArrayList<>();
        node.fields().forEachRemaining(out::add);
        return out;
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String directoryOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(0, slash + 1) : "";
    }

    private static String normalize(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private static String resolveRelative(String baseDir, String ref) {
        String combined = ref.startsWith("/") ? ref.substring(1) : baseDir + ref;
        String[] parts = combined.split("/");
        List<String> resolved = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!resolved.isEmpty()) {
                    resolved.remove(resolved.size() - 1);
                }
            } else {
                resolved.add(part);
            }
        }
        return String.join("/", resolved);
    }

    private static String relativePath(String fromDir, String target) {
        String[] from = fromDir.isEmpty() ? new String[0] : fromDir.replaceAll("/+$", "").split("/");
        String[] to = target.split("/");
        int common = 0;
        while (common < from.length && common < to.length - 1 && from[common].equals(to[common])) {
            common++;
        }
        StringBuilder rel = new StringBuilder();
        for (int i = common; i < from.length; i++) {
            rel.append("../");
        }
        for (int i = common; i < to.length; i++) {
            rel.append(to[i]);
            if (i < to.length - 1) {
                rel.append('/');
            }
        }
        return rel.length() == 0 ? basename(target) : rel.toString();
    }
}
