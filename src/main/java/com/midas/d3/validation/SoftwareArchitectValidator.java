package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

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
            }
        }
    }
}
