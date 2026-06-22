package com.midas.d3.validation;



import com.fasterxml.jackson.core.JsonProcessingException;

import com.fasterxml.jackson.databind.JsonNode;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.midas.d3.agent.implementation.ArchitectureSurfaceSlicer;

import com.midas.d3.agent.implementation.HybridExecutionModel;

import org.springframework.stereotype.Component;



import java.util.ArrayList;

import java.util.List;

import java.util.Set;



/**

 * Validates Agent 2 (Software Architect) output against the v2, runtime-aware schema.

 *

 * <p>Unlike the legacy validator (which hard-required PostgreSQL tables + REST endpoints),

 * infrastructure is now <b>conditional on the chosen {@code architecture_style}</b>:

 * <ul>

 *   <li>A relational/embedded schema is required only when {@code data_persistence.type}

 *       is RELATIONAL or EMBEDDED_DB.</li>

 *   <li>REST {@code api_contracts} are required only for server architectures

 *       (CLIENT_SERVER / SERVERLESS / MONOLITH). Client-only/static/SPA designs MUST NOT be

 *       forced to invent endpoints.</li>

 *   <li>When {@code runtime_environment.execution_model} is HYBRID, {@code file_layout} must

 *       contain both client and server paths (monorepo guard).</li>

 * </ul>

 */

@Component

public class SoftwareArchitectValidator extends AbstractGoalKeeperValidator {



    private static final Set<String> VALID_HTTP_METHODS =

            Set.of("GET", "POST", "PUT", "DELETE", "PATCH");



    /** Architecture styles that genuinely own a server surface and therefore need API contracts. */

    private static final Set<String> SERVER_STYLES =

            Set.of("CLIENT_SERVER", "SERVERLESS", "MONOLITH");



    /** Persistence types that require an explicit table/column schema. */

    private static final Set<String> DB_PERSISTENCE_TYPES =

            Set.of("RELATIONAL", "EMBEDDED_DB");



    public SoftwareArchitectValidator(ObjectMapper objectMapper) {

        super(objectMapper);

    }



    @Override public String agentName() { return "SoftwareArchitect"; }

    @Override public String stage()     { return "ARCHITECTURE_DESIGN"; }



    @Override

    public JsonNode validate(String rawJson) throws ValidationHookException {

        return validateWithTechnicalSpec(rawJson, null);

    }



    /**

     * Validates architect output with optional upstream {@code technicalSpec} for HYBRID monorepo checks.

     */

    public JsonNode validateWithTechnicalSpec(String rawJson, JsonNode technicalSpec)

            throws ValidationHookException {

        if (rawJson == null || rawJson.isBlank()) {

            throw new ValidationHookException(agentName(), stage(),

                    "LLM output is null or blank — no JSON to validate.");

        }



        JsonNode root;

        try {

            root = objectMapper.readTree(rawJson.strip());

        } catch (JsonProcessingException e) {

            throw new ValidationHookException(agentName(), stage(),

                    "JSON parse error: " + e.getOriginalMessage());

        }



        if (!root.isObject()) {

            throw new ValidationHookException(agentName(), stage(),

                    "Expected JSON object at root, got: " + root.getNodeType());

        }



        List<String> violations = new ArrayList<>();

        collectViolations(root, violations);

        validateHybridMonorepoLayout(root, technicalSpec, violations);



        if (!violations.isEmpty()) {

            throw new ValidationHookException(agentName(), stage(), violations);

        }



        return root;

    }



    @Override

    protected void collectViolations(JsonNode root, List<String> violations) {

        requireBooleanField(root, "has_external_integrations", violations);

        requireStringField(root, "architecture_style", violations);

        requireObjectField(root, "tech_stack", violations);

        requireArrayField(root, "components", 1, violations);

        requireArrayField(root, "file_layout", 1, violations);



        String style = root.path("architecture_style").asText("").toUpperCase();



        validatePersistence(root.get("data_persistence"), violations);

        validateApiContracts(root, style, violations);

    }



    static void validateHybridMonorepoLayout(JsonNode root, JsonNode technicalSpec, List<String> violations) {

        if (technicalSpec == null || !HybridExecutionModel.isHybrid(technicalSpec)) {

            return;

        }



        JsonNode fileLayout = root.get("file_layout");

        if (fileLayout == null || !fileLayout.isArray()) {

            return;

        }



        boolean hasClientPaths = false;

        boolean hasServerPaths = false;

        for (JsonNode entry : fileLayout) {

            if (!entry.isTextual()) {

                continue;

            }

            String path = entry.asText();

            if (ArchitectureSurfaceSlicer.isClientPath(path)) {

                hasClientPaths = true;

            }

            if (ArchitectureSurfaceSlicer.isServerPath(path)) {

                hasServerPaths = true;

            }

        }



        if (!hasClientPaths) {

            violations.add("REJECTED: HYBRID mode requires client-side paths in file_layout "

                    + "(e.g. frontend/manifest.json, frontend/src/popup.html) — none detected.");

        }

        if (!hasServerPaths) {

            violations.add("REJECTED: HYBRID mode requires server-side paths in file_layout "

                    + "(e.g. backend/pom.xml, backend/src/main/java/...) — none detected.");

        }

    }



    private void validatePersistence(JsonNode persistence, List<String> violations) {

        if (persistence == null || persistence.isNull() || persistence.isMissingNode()) {

            // Persistence block is optional for client tools; absence means "no DB".

            return;

        }

        if (!persistence.isObject()) {

            violations.add("Field 'data_persistence' must be a JSON object when present.");

            return;

        }

        String type = persistence.path("type").asText("").toUpperCase();

        JsonNode schema = persistence.get("schema");



        if (DB_PERSISTENCE_TYPES.contains(type)) {

            if (schema == null || !schema.isArray() || schema.isEmpty()) {

                violations.add("data_persistence.type is '" + type

                        + "' but 'schema' is missing or empty — a table schema is required.");

            } else {

                validateTables(schema, violations);

            }

        }

        // For NONE/BROWSER_STORAGE/LOCAL_FILE we intentionally do NOT require a relational schema.

    }



    private void validateTables(JsonNode tables, List<String> violations) {

        for (int i = 0; i < tables.size(); i++) {

            JsonNode table = tables.get(i);

            if (!table.isObject()) { violations.add("data_persistence.schema[" + i + "] must be an object."); continue; }

            if (!table.has("table_name") || table.get("table_name").asText().isBlank()) {

                violations.add("data_persistence.schema[" + i + "].table_name is missing or blank.");

            }

            JsonNode columns = table.get("columns");

            if (columns == null || !columns.isArray() || columns.isEmpty()) {

                violations.add("data_persistence.schema[" + i + "].columns must be a non-empty array.");

            }

        }

    }



    private void validateApiContracts(JsonNode root, String style, List<String> violations) {

        JsonNode endpoints = root.get("api_contracts");

        boolean serverStyle = SERVER_STYLES.contains(style);



        if (serverStyle) {

            if (endpoints == null || !endpoints.isArray() || endpoints.isEmpty()) {

                violations.add("architecture_style '" + style

                        + "' requires a non-empty 'api_contracts' array.");

                return;

            }

        }



        // When present (in any style), each contract must be well-formed.

        if (endpoints != null && endpoints.isArray()) {

            for (int i = 0; i < endpoints.size(); i++) {

                JsonNode ep = endpoints.get(i);

                if (!ep.isObject()) { violations.add("api_contracts[" + i + "] must be an object."); continue; }

                String method = ep.has("method") ? ep.get("method").asText("").toUpperCase() : "";

                if (!VALID_HTTP_METHODS.contains(method)) {

                    violations.add("api_contracts[" + i + "].method must be one of " + VALID_HTTP_METHODS

                            + ", got: '" + method + "'");

                }

                if (!ep.has("path") || ep.get("path").asText().isBlank()) {

                    violations.add("api_contracts[" + i + "].path is missing or blank.");

                }

                if (serverStyle) {
                    validateRigidApiContract(ep, i, violations);
                }

            }

        }

    }

    private void validateRigidApiContract(JsonNode ep, int index, List<String> violations) {
        if (!ep.has("request_params") || !ep.get("request_params").isArray()) {
            violations.add("api_contracts[" + index + "].request_params must be an array "
                    + "with exact field names (e.g. [{\"name\":\"file\",\"location\":\"form-data\",\"type\":\"MultipartFile\"}]).");
        } else {
            String method = ep.has("method") ? ep.get("method").asText("").toUpperCase() : "";
            boolean allowsEmptyParams = "GET".equals(method) || "DELETE".equals(method);
            if (!allowsEmptyParams && ep.get("request_params").isEmpty()) {
                violations.add("api_contracts[" + index + "].request_params must list exact field names for "
                        + method + " requests.");
            }
        }
        if (!ep.has("response_format") || ep.get("response_format").isNull() || ep.get("response_format").isMissingNode()) {
            violations.add("api_contracts[" + index + "].response_format is required "
                    + "(e.g. {\"type\":\"string\",\"example\":\"File uploaded successfully\"} "
                    + "or {\"type\":\"json\",\"fields\":[\"message\"]}).");
        }
    }

}


