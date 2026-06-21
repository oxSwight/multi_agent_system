package com.midas.d3.statemachine.remediation;

import java.util.List;

public record RemediationPlan(
        RemediationMode mode,
        List<String> affectedPaths,
        List<String> affectedFeatures
) {
    public static RemediationPlan fullRegen() {
        return new RemediationPlan(RemediationMode.FULL_REGEN, List.of(), List.of());
    }
}
