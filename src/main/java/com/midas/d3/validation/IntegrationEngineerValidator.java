package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Validates Agent 3 (Integration Engineer) output against the v2 schema.
 *
 * <pre>
 * {
 *   "has_external_integrations": boolean,
 *   "external_services": [{"name","purpose","auth","rate_limit_strategy","parsing_approach"}],
 *   "client_side_constraints": [String]?
 * }
 * </pre>
 *
 * <p>Self-contained products are first-class: when {@code has_external_integrations} is false,
 * {@code external_services} MUST be empty and the agent is not forced to invent integrations.
 */
@Component
public class IntegrationEngineerValidator extends AbstractGoalKeeperValidator {

    public IntegrationEngineerValidator(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override public String agentName() { return "IntegrationEngineer"; }
    @Override public String stage()     { return "INTEGRATION_STRATEGY"; }

    @Override
    protected void collectViolations(JsonNode root, List<String> violations) {
        requireBooleanField(root, "has_external_integrations", violations);
        requireArrayField(root, "external_services", 0, violations);

        JsonNode clientConstraints = root.get("client_side_constraints");
        if (clientConstraints != null && !clientConstraints.isNull() && !clientConstraints.isArray()) {
            violations.add("Field 'client_side_constraints' must be an array when present.");
        }

        JsonNode hasIntegrations = root.get("has_external_integrations");
        JsonNode services = root.get("external_services");
        if (hasIntegrations == null || !hasIntegrations.isBoolean() || services == null || !services.isArray()) {
            return; // shape errors already reported above
        }

        if (!hasIntegrations.asBoolean()) {
            if (!services.isEmpty()) {
                violations.add("has_external_integrations is false but 'external_services' is non-empty "
                        + "— a self-contained product must not declare external services.");
            }
            return;
        }

        // has_external_integrations == true → each service entry must be fully specified.
        if (services.isEmpty()) {
            violations.add("has_external_integrations is true but 'external_services' is empty.");
        }
        for (int i = 0; i < services.size(); i++) {
            JsonNode svc = services.get(i);
            if (!svc.isObject()) {
                violations.add("external_services[" + i + "] must be an object.");
                continue;
            }
            requireNonBlank(svc, "name", i, violations);
            requireNonBlank(svc, "purpose", i, violations);
            requireNonBlank(svc, "parsing_approach", i, violations);
        }
    }

    private void requireNonBlank(JsonNode svc, String field, int idx, List<String> violations) {
        if (!svc.has(field) || svc.get(field).asText().isBlank()) {
            violations.add("external_services[" + idx + "]." + field + " is missing or blank.");
        }
    }
}
