package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class SystemAnalystValidator extends AbstractGoalKeeperValidator {

    public SystemAnalystValidator(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override public String agentName() { return "SystemAnalyst"; }
    @Override public String stage()     { return "SYSTEM_ANALYSIS"; }

    static final List<String> ADVERSARIAL_MARKERS = List.of(
            "ignore previous instruction",
            "ignore all previous",
            "ignore the above",
            "ignore everything",
            "ignore prior instruction",
            "disregard previous",
            "disregard all previous",
            "disregard the above",
            "disregard your instruction",
            "forget previous instruction",
            "forget all previous",
            "forget everything",
            "forget your instruction",
            "reveal your instruction",
            "reveal your system prompt",
            "reveal your prompt",
            "reveal the system prompt",
            "show your system prompt",
            "show me your prompt",
            "print your instruction",
            "what is your system prompt",
            "repeat your instruction",
            "your system prompt",
            "you are now",
            "act as a different",
            "act as an ai",
            "pretend to be",
            "pretend you are",
            "role-play as",
            "roleplay as",
            "do anything now",
            "developer mode",
            "jailbreak",
            "tell me a joke");

    @Override
    protected void collectViolations(JsonNode root, List<String> violations) {
        requireStringField(root, "business_goal", violations);
        requireArrayField(root, "core_features", 1, violations);

        rejectAdversarialLeakage(root, violations);

        validateRuntimeEnvironment(root.get("runtime_environment"), violations);

        JsonNode nfr = root.has("non_functional_requirements")
                ? root.get("non_functional_requirements")
                : root.get("performance_constraints");
        if (nfr != null && !nfr.isNull() && !nfr.isArray()) {
            violations.add("Field 'non_functional_requirements' (or legacy 'performance_constraints') must be an array.");
        }

        JsonNode edgeCases = root.get("edge_cases_and_handling");
        if (edgeCases == null || edgeCases.isNull()) {
            violations.add("Missing required array field: 'edge_cases_and_handling'");
        } else if (!edgeCases.isArray()) {
            violations.add("Field 'edge_cases_and_handling' must be an array.");
        } else {
            for (int i = 0; i < edgeCases.size(); i++) {
                JsonNode entry = edgeCases.get(i);
                if (!entry.isObject()) {
                    violations.add("edge_cases_and_handling[" + i + "] must be an object.");
                    continue;
                }
                if (!entry.has("case") || entry.get("case").asText().isBlank()) {
                    violations.add("edge_cases_and_handling[" + i + "].case is missing or blank.");
                }
                if (!entry.has("solution") || entry.get("solution").asText().isBlank()) {
                    violations.add("edge_cases_and_handling[" + i + "].solution is missing or blank.");
                }
            }
        }
    }

    private void validateRuntimeEnvironment(JsonNode env, List<String> violations) {
        if (env == null || env.isNull() || env.isMissingNode()) {
            violations.add("Missing required object field: 'runtime_environment' "
                    + "(the analyst must define the product's runtime boundary).");
            return;
        }
        if (!env.isObject()) {
            violations.add("Field 'runtime_environment' must be a JSON object.");
            return;
        }
        requireStringField(env, "execution_model", violations);
        requireStringField(env, "deployment_target", violations);
        requireStringField(env, "persistence", violations);
        requireBooleanField(env, "requires_backend", violations);

        JsonNode forbidden = env.get("forbidden_infrastructure");
        if (forbidden != null && !forbidden.isNull() && !forbidden.isArray()) {
            violations.add("runtime_environment.forbidden_infrastructure must be an array when present.");
        }
    }

    private void rejectAdversarialLeakage(JsonNode root, List<String> violations) {
        JsonNode statusNode = root.get("input_status");
        if (statusNode != null && statusNode.isTextual()
                && "REJECTED".equalsIgnoreCase(statusNode.asText().strip())) {
            return;
        }

        List<String> inspected = new ArrayList<>();
        collectText(root.get("business_goal"), inspected);
        collectText(root.get("core_features"), inspected);
        collectText(root.get("non_functional_requirements"), inspected);
        collectText(root.get("performance_constraints"), inspected);
        collectText(root.get("edge_cases_and_handling"), inspected);

        for (String text : inspected) {
            String lower = text.toLowerCase(Locale.ROOT);
            for (String marker : ADVERSARIAL_MARKERS) {
                if (lower.contains(marker)) {
                    violations.add("Ingress Firewall: output contains a leaked adversarial/meta-instruction "
                            + "('" + marker + "') instead of a software specification. Treating the input as "
                            + "a prompt-injection / jailbreak attempt and rejecting.");
                    return;
                }
            }
        }
    }

    private void collectText(JsonNode node, List<String> sink) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isTextual()) {
            String value = node.asText();
            if (!value.isBlank()) {
                sink.add(value);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectText(child, sink));
        } else if (node.isObject()) {
            node.forEach(child -> collectText(child, sink));
        }
    }
}
