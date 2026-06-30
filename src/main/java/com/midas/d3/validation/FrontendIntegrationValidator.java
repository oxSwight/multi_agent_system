package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structural checks for browser-extension / popup frontends — catches orphan JS modules,
 * broken HTML script wiring, MV3 manifest gaps, and UX anti-patterns the LLM often emits.
 */
final class FrontendIntegrationValidator {

    private static final Pattern SCRIPT_SRC = Pattern.compile(
            "<script[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ID = Pattern.compile(
            "\\bid=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern GET_ELEMENT_BY_ID = Pattern.compile(
            "getElementById\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
    private static final Pattern QUERY_SELECTOR_HASH = Pattern.compile(
            "querySelector(?:All)?\\(\\s*['\"]#([^'\"\\s]+)");
    private static final Pattern FETCH_URL = Pattern.compile(
            "fetch\\(\\s*['\"`]([^'\"`]+)['\"`]");
    /** An OpenAPI-style path parameter, e.g. the {@code {profileId}} in {@code /api/resumes/{profileId}}. */
    private static final Pattern PATH_PARAM = Pattern.compile("\\{[^/{}]+}");
    /** An element id created/assigned in JS: {@code id="x"}, {@code id: 'x'}, {@code el.id = 'x'}, setAttribute. */
    private static final Pattern JS_DEFINED_ID = Pattern.compile(
            "(?:\\bid\\s*[:=]\\s*|setAttribute\\(\\s*['\"]id['\"]\\s*,\\s*)['\"]([^'\"]+)['\"]");
    /** A {@code chrome.*.sendMessage(} call — the start of a runtime message dispatch. */
    private static final Pattern SEND_MESSAGE = Pattern.compile("sendMessage\\s*\\(");
    /** The action/type discriminator inside a message payload object. */
    private static final Pattern ACTION_IN_PAYLOAD = Pattern.compile(
            "(?:action|type)\\s*:\\s*['\"]([^'\"]+)['\"]");

    private FrontendIntegrationValidator() {}

    static void validateSourceFiles(JsonNode sourceFiles, JsonNode architecture, List<String> violations) {
        if (sourceFiles == null || !sourceFiles.isObject()) {
            return;
        }

        Set<String> paths = new HashSet<>();
        Iterator<Map.Entry<String, JsonNode>> fields = sourceFiles.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getValue().isTextual()) {
                paths.add(normalizePath(entry.getKey()));
            }
        }

        fields = sourceFiles.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String path = entry.getKey();
            if (!entry.getValue().isTextual()) {
                continue;
            }
            String content = entry.getValue().asText();
            String lower = path.toLowerCase(Locale.ROOT);

            if (lower.endsWith(".html")) {
                validateHtmlScriptWiring(path, content, paths, violations);
            }
            if (lower.endsWith(".js") && !isServiceWorkerScript(lower)) {
                validateExtensionJs(path, content, violations);
            }
            if (lower.endsWith(".css") && lower.contains("popup")) {
                validatePopupCss(path, content, violations);
            }
            if (lower.endsWith("manifest.json")) {
                validateManifest(path, content, sourceFiles, violations);
            }
        }

        validateApiContractUsage(sourceFiles, architecture, violations);
        validateDomIdConsistency(sourceFiles, violations);
        validateMessageActionContracts(sourceFiles, violations);
    }

    /**
     * Cross-file UI contract: every element id a script queries (getElementById / querySelector('#…'))
     * must exist in some generated HTML or be created in JS. Catches the popup.js↔popup.html id drift
     * where a handler binds to an id the markup never defines, so the feature silently no-ops.
     */
    private static void validateDomIdConsistency(JsonNode sourceFiles, List<String> violations) {
        Set<String> htmlIds = collectHtmlIds(sourceFiles);
        if (htmlIds.isEmpty()) {
            return;
        }
        Set<String> definedInJs = collectJsDefinedIds(sourceFiles);

        Iterator<Map.Entry<String, JsonNode>> fields = sourceFiles.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String path = entry.getKey();
            if (!entry.getValue().isTextual() || !isImportableSource(path)) {
                continue;
            }
            for (String id : collectQueriedIds(entry.getValue().asText())) {
                if (!htmlIds.contains(id) && !definedInJs.contains(id)) {
                    violations.add("[" + path + "] queries element id ['" + id + "'] that exists in no generated "
                            + "HTML and is never created in JS — use the exact id present in the markup "
                            + "(getElementById/querySelector must match an existing id), or render the element.");
                }
            }
        }
    }

    /**
     * Cross-file message contract: every runtime message action a script sends
     * ({@code chrome.runtime/tabs.sendMessage({action|type:'X'})}) must be referenced by some
     * {@code onMessage} listener. Catches the content_script→background gap where a message like
     * {@code 'saveSemanticData'} is dispatched but no service worker branches on it.
     */
    private static void validateMessageActionContracts(JsonNode sourceFiles, List<String> violations) {
        StringBuilder handlerContext = new StringBuilder();
        Iterator<Map.Entry<String, JsonNode>> fields = sourceFiles.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getValue().isTextual() && entry.getValue().asText().contains("onMessage")) {
                handlerContext.append(entry.getValue().asText()).append('\n');
            }
        }
        String handlers = handlerContext.toString();

        fields = sourceFiles.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String path = entry.getKey();
            if (!entry.getValue().isTextual()) {
                continue;
            }
            String content = entry.getValue().asText();
            if (!content.contains("sendMessage")) {
                continue;
            }
            for (String action : extractSentActions(content)) {
                if (!handlers.contains("'" + action + "'") && !handlers.contains("\"" + action + "\"")) {
                    violations.add("[" + path + "] sends runtime message action ['" + action + "'] but no "
                            + "onMessage listener references it — the receiving script (background / service "
                            + "worker) must branch on this action so the message is actually handled.");
                }
            }
        }
    }

    private static Set<String> collectJsDefinedIds(JsonNode sourceFiles) {
        Set<String> ids = new HashSet<>();
        Iterator<Map.Entry<String, JsonNode>> fields = sourceFiles.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!entry.getValue().isTextual() || !isImportableSource(entry.getKey())) {
                continue;
            }
            Matcher matcher = JS_DEFINED_ID.matcher(entry.getValue().asText());
            while (matcher.find()) {
                ids.add(matcher.group(1));
            }
        }
        return ids;
    }

    /** Extracts action/type strings from message payloads, scoped to the window after each sendMessage(. */
    private static Set<String> extractSentActions(String content) {
        Set<String> actions = new HashSet<>();
        Matcher send = SEND_MESSAGE.matcher(content);
        while (send.find()) {
            int start = send.end();
            int end = Math.min(content.length(), start + 200);
            Matcher action = ACTION_IN_PAYLOAD.matcher(content.substring(start, end));
            if (action.find()) {
                actions.add(action.group(1));
            }
        }
        return actions;
    }

    static void validateTestAgainstSource(String testPath,
                                          String testContent,
                                          JsonNode generatedSource,
                                          JsonNode architecture,
                                          List<String> violations) {
        if (testContent == null || testContent.isBlank() || generatedSource == null || !generatedSource.isObject()) {
            return;
        }

        String lower = testPath.toLowerCase(Locale.ROOT);
        boolean isJsTest = lower.contains(".test.") || lower.contains(".spec.") || lower.contains("__tests__");
        if (!isJsTest) {
            return;
        }

        boolean importsSource = false;
        Iterator<Map.Entry<String, JsonNode>> sourceFields = generatedSource.fields();
        while (sourceFields.hasNext()) {
            Map.Entry<String, JsonNode> entry = sourceFields.next();
            String sourcePath = entry.getKey();
            if (!entry.getValue().isTextual() || !isImportableSource(sourcePath)) {
                continue;
            }
            String baseName = fileBaseName(sourcePath);
            if (testContent.contains(baseName)
                    || testContent.contains(relativeImportPath(sourcePath))
                    || testContent.contains("require('" + baseName)
                    || testContent.contains("require(\"" + baseName)) {
                importsSource = true;
                break;
            }
        }

        if (!importsSource && referencesDom(testContent)) {
            violations.add("Test [" + testPath + "] references DOM APIs but does not import/require "
                    + "any module from generatedSourceCode — tests must exercise actual source files.");
        }

        Set<String> htmlIds = collectHtmlIds(generatedSource);
        if (!htmlIds.isEmpty()) {
            // A test may legitimately build its own mock DOM: a content script runs against arbitrary
            // external pages, so its test creates synthetic inputs with realistic ids (firstName,
            // email, …) that are NOT in the extension's own HTML. Accept any id the test itself
            // defines; flag only an id present in neither the generated HTML nor the test's own setup —
            // the real "asserts on a popup element that does not exist" drift.
            Set<String> testDefinedIds = collectDefinedIds(testContent);
            for (String queriedId : collectQueriedIds(testContent)) {
                if (!htmlIds.contains(queriedId) && !testDefinedIds.contains(queriedId)) {
                    violations.add("Test [" + testPath + "] queries element id ['" + queriedId
                            + "'] which exists in neither any generated HTML file nor the test's own DOM setup.");
                }
            }
        }

        validateTestApiUrls(testPath, testContent, architecture, violations);
    }

    private static void validateHtmlScriptWiring(String htmlPath,
                                                   String html,
                                                   Set<String> allPaths,
                                                   List<String> violations) {
        if (!htmlPath.toLowerCase(Locale.ROOT).contains("popup")) {
            return;
        }

        Matcher matcher = SCRIPT_SRC.matcher(html);
        List<String> scriptRefs = new ArrayList<>();
        while (matcher.find()) {
            scriptRefs.add(matcher.group(1));
        }

        if (scriptRefs.isEmpty()) {
            violations.add("[" + htmlPath + "] has no <script src=\"...\"> tags — popup HTML must load "
                    + "its JavaScript entry point(s). Never reference scripts that are not in file_layout.");
            return;
        }

        String htmlDir = directoryOf(htmlPath);
        for (String ref : scriptRefs) {
            String resolved = resolveRelative(htmlDir, ref);
            if (!allPaths.contains(resolved)) {
                violations.add("[" + htmlPath + "] references missing script ['" + ref
                        + "'] (resolved: [" + resolved + "]) — every <script src> must point to a "
                        + "generated file. Do not invent popup.js if it is not in file_layout.");
            }
        }

        for (String path : allPaths) {
            if (!isPopupCompanionJs(path, htmlPath)) {
                continue;
            }
            boolean referenced = scriptRefs.stream()
                    .anyMatch(ref -> resolveRelative(htmlDir, ref).equals(path));
            if (!referenced) {
                violations.add("Orphan JavaScript module [" + path + "] is in file_layout but not loaded "
                        + "by [" + htmlPath + "] — wire it via <script src> or a single entry script that "
                        + "imports/inits all popup modules.");
            }
        }
    }

    /**
     * The <b>file-local</b> content rules (no cross-file context needed): forbidden browser dialogs in
     * a non-worker JS module, and the box-sizing reset in a popup stylesheet. Exposed so the per-file
     * generation loop can enforce them at generation time — where a violation drives an immediate,
     * targeted retry of that one file with feedback — instead of only at the assembled-envelope gate,
     * where the same violation dead-ends the whole pass. Identical rules, applied earlier.
     */
    static void validateSingleFileContent(String path, String content, List<String> violations) {
        if (path == null || content == null) {
            return;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".js") && !isServiceWorkerScript(lower)) {
            validateExtensionJs(path, content, violations);
        }
        if (lower.endsWith(".css") && lower.contains("popup")) {
            validatePopupCss(path, content, violations);
        }
    }

    private static void validateExtensionJs(String path, String content, List<String> violations) {
        String lower = content.toLowerCase(Locale.ROOT);
        if (lower.contains("window.alert(") || lower.contains("alert(")) {
            violations.add("[" + path + "] uses alert() — forbidden in extension popups. Use inline "
                    + "status/toast UI elements defined in the HTML instead.");
        }
        if (lower.contains("window.prompt(") || lower.contains("prompt(")) {
            violations.add("[" + path + "] uses prompt() — forbidden. Use in-popup form UI for user input.");
        }
    }

    private static void validatePopupCss(String path, String content, List<String> violations) {
        if (!content.toLowerCase(Locale.ROOT).contains("box-sizing")) {
            violations.add("[" + path + "] missing box-sizing: border-box reset — required for narrow "
                    + "extension popups to prevent width:100% overflow.");
        }
    }

    private static void validateManifest(String path,
                                         String content,
                                         JsonNode sourceFiles,
                                         List<String> violations) {
        String lower = content.toLowerCase(Locale.ROOT);
        boolean needsHostPermissions = sourceUsesCrossOriginFetch(sourceFiles);
        if (needsHostPermissions && !lower.contains("host_permissions")) {
            violations.add("[" + path + "] missing host_permissions — MV3 requires host_permissions "
                    + "for fetch() calls to backend APIs (e.g. http://localhost:8080/*).");
        }
        if (lower.contains("\"storage\"") && !sourceUsesChromeStorage(sourceFiles)) {
            violations.add("[" + path + "] declares storage permission but no generated JS uses "
                    + "chrome.storage.local — persist extension state instead of volatile service-worker RAM.");
        }
    }

    private static void validateApiContractUsage(JsonNode sourceFiles,
                                                 JsonNode architecture,
                                                 List<String> violations) {
        Set<String> contractPaths = collectContractPaths(architecture);
        if (contractPaths.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = sourceFiles.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!entry.getValue().isTextual() || !entry.getKey().toLowerCase(Locale.ROOT).endsWith(".js")) {
                continue;
            }
            String content = entry.getValue().asText();
            Matcher fetchMatcher = FETCH_URL.matcher(content);
            while (fetchMatcher.find()) {
                String url = fetchMatcher.group(1);
                if (!urlContainsContractPath(url, contractPaths)) {
                    violations.add("[" + entry.getKey() + "] fetch() uses URL ['" + url
                            + "'] which does not match any api_contracts.path "
                            + contractPaths + " — do not invent endpoints or field names.");
                }
            }
        }
    }

    private static void validateTestApiUrls(String testPath,
                                            String testContent,
                                            JsonNode architecture,
                                            List<String> violations) {
        Set<String> contractPaths = collectContractPaths(architecture);
        if (contractPaths.isEmpty()) {
            return;
        }

        Matcher fetchMatcher = FETCH_URL.matcher(testContent);
        while (fetchMatcher.find()) {
            String url = fetchMatcher.group(1);
            if (!urlContainsContractPath(url, contractPaths)) {
                violations.add("Test [" + testPath + "] mocks fetch to ['" + url
                        + "'] which does not match api_contracts " + contractPaths
                        + " — test the actual API contract from architecture, not a hallucinated endpoint.");
            }
        }
    }

    private static boolean urlContainsContractPath(String url, Set<String> contractPaths) {
        for (String path : contractPaths) {
            if (contractPathPattern(path).matcher(url).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compiles an OpenAPI-style contract path into a regex that matches the same endpoint as written
     * in client code. A naive {@code url.contains(path)} produced a systematic false positive: a
     * contract path declares a parameter with a brace placeholder ({@code /api/resumes/{profileId}}),
     * but correct client code fills it with a JS template literal
     * ({@code `${API_BASE_URL}/api/resumes/${selectedProfileId}`}), an Express param ({@code :profileId}),
     * or a concrete value — the literal substring {@code {profileId}} is never present, so every
     * correctly-parameterized endpoint read as an "invented" one and wedged CODE_GENERATION.
     *
     * <p>Each {@code {param}} placeholder becomes a single-path-segment wildcard ({@code [^/?#]+}),
     * while literal text is matched verbatim. The pattern is searched as a substring ({@code find()}),
     * so a base-URL prefix and a query/hash suffix are tolerated, yet a genuinely different path
     * (different literal segments) still fails to match.
     */
    private static Pattern contractPathPattern(String contractPath) {
        Matcher params = PATH_PARAM.matcher(contractPath);
        StringBuilder regex = new StringBuilder();
        int last = 0;
        while (params.find()) {
            if (params.start() > last) {
                regex.append(Pattern.quote(contractPath.substring(last, params.start())));
            }
            regex.append("[^/?#]+");
            last = params.end();
        }
        if (last < contractPath.length()) {
            regex.append(Pattern.quote(contractPath.substring(last)));
        }
        return Pattern.compile(regex.toString());
    }

    private static boolean sourceUsesCrossOriginFetch(JsonNode sourceFiles) {
        Iterator<Map.Entry<String, JsonNode>> fields = sourceFiles.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!entry.getValue().isTextual()) {
                continue;
            }
            String content = entry.getValue().asText().toLowerCase(Locale.ROOT);
            if (content.contains("fetch(") && (content.contains("localhost") || content.contains("http://") || content.contains("https://"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean sourceUsesChromeStorage(JsonNode sourceFiles) {
        Iterator<Map.Entry<String, JsonNode>> fields = sourceFiles.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!entry.getValue().isTextual()) {
                continue;
            }
            if (entry.getValue().asText().contains("chrome.storage")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPopupCompanionJs(String jsPath, String htmlPath) {
        String lower = jsPath.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".js") || isServiceWorkerScript(lower)) {
            return false;
        }
        if (!lower.contains("popup") && !sameDirectory(jsPath, htmlPath)) {
            return false;
        }
        return sameDirectory(jsPath, htmlPath);
    }

    private static boolean isServiceWorkerScript(String lowerPath) {
        return lowerPath.contains("background.js")
                || lowerPath.contains("content_script")
                || lowerPath.contains("service_worker");
    }

    private static boolean isImportableSource(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".js") || lower.endsWith(".ts") || lower.endsWith(".tsx");
    }

    private static boolean referencesDom(String testContent) {
        return testContent.contains("getElementById")
                || testContent.contains("querySelector")
                || testContent.contains("document.");
    }

    private static Set<String> collectHtmlIds(JsonNode generatedSource) {
        Set<String> ids = new HashSet<>();
        Iterator<Map.Entry<String, JsonNode>> fields = generatedSource.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!entry.getValue().isTextual() || !entry.getKey().toLowerCase(Locale.ROOT).endsWith(".html")) {
                continue;
            }
            Matcher matcher = HTML_ID.matcher(entry.getValue().asText());
            while (matcher.find()) {
                ids.add(matcher.group(1));
            }
        }
        return ids;
    }

    /** Ids the given source/test defines itself — via an {@code id="x"} literal or a JS id assignment. */
    private static Set<String> collectDefinedIds(String content) {
        Set<String> ids = new HashSet<>();
        Matcher html = HTML_ID.matcher(content);
        while (html.find()) {
            ids.add(html.group(1));
        }
        Matcher js = JS_DEFINED_ID.matcher(content);
        while (js.find()) {
            ids.add(js.group(1));
        }
        return ids;
    }

    private static Set<String> collectQueriedIds(String testContent) {
        Set<String> ids = new HashSet<>();
        Matcher byId = GET_ELEMENT_BY_ID.matcher(testContent);
        while (byId.find()) {
            ids.add(byId.group(1));
        }
        Matcher byHash = QUERY_SELECTOR_HASH.matcher(testContent);
        while (byHash.find()) {
            ids.add(byHash.group(1));
        }
        return ids;
    }

    private static String fileBaseName(String path) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String relativeImportPath(String sourcePath) {
        String normalized = sourcePath.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static String normalizePath(String path) {
        return path.replace('\\', '/');
    }

    private static String directoryOf(String path) {
        String normalized = normalizePath(path);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(0, slash + 1) : "";
    }

    private static boolean sameDirectory(String a, String b) {
        return directoryOf(a).equals(directoryOf(b));
    }

    private static Set<String> collectContractPaths(JsonNode architecture) {
        Set<String> contractPaths = new HashSet<>();
        if (architecture == null || !architecture.isObject()) {
            return contractPaths;
        }

        JsonNode contracts = architecture.get("api_contracts");
        if (contracts != null && contracts.isArray()) {
            for (JsonNode contract : contracts) {
                if (contract.has("path") && contract.get("path").isTextual()) {
                    contractPaths.add(contract.get("path").asText().strip());
                }
            }
        }

        JsonNode analystContracts = architecture.get("api_contract");
        if (analystContracts != null && analystContracts.isArray()) {
            for (JsonNode contract : analystContracts) {
                if (contract.has("path") && contract.get("path").isTextual()) {
                    contractPaths.add(contract.get("path").asText().strip());
                }
            }
        }
        return contractPaths;
    }

    private static String resolveRelative(String baseDir, String ref) {
        if (ref.startsWith("/") || ref.contains("://")) {
            return normalizePath(ref);
        }
        String combined = baseDir + ref;
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
}
