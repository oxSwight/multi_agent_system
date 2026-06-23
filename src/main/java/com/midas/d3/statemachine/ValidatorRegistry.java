package com.midas.d3.statemachine;

import com.midas.d3.validation.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Maps each processing {@link MidasState} to its dedicated {@link GoalKeeperValidator}.
 *
 * <p>Non-processing states (IDLE, COMPLETED, ERROR) have no validator — callers
 * receive {@link Optional#empty()} and must handle that case gracefully.
 */
@Slf4j
@Component
public class ValidatorRegistry {

    private final Map<MidasState, GoalKeeperValidator> registry = new EnumMap<>(MidasState.class);

    private final SystemAnalystValidator        systemAnalystValidator;
    private final SoftwareArchitectValidator    architectValidator;
    private final IntegrationEngineerValidator  integrationValidator;
    private final ImplementationEngineerValidator implementationValidator;
    private final QaEngineerValidator           qaValidator;
    private final BuildVerificationValidator    buildVerificationValidator;
    private final SecOpsEngineerValidator       secOpsValidator;
    private final ControllerValidator           controllerValidator;

    public ValidatorRegistry(
            SystemAnalystValidator        systemAnalystValidator,
            SoftwareArchitectValidator    architectValidator,
            IntegrationEngineerValidator  integrationValidator,
            ImplementationEngineerValidator implementationValidator,
            QaEngineerValidator           qaValidator,
            BuildVerificationValidator    buildVerificationValidator,
            SecOpsEngineerValidator       secOpsValidator,
            ControllerValidator           controllerValidator) {

        this.systemAnalystValidator  = systemAnalystValidator;
        this.architectValidator      = architectValidator;
        this.integrationValidator    = integrationValidator;
        this.implementationValidator = implementationValidator;
        this.qaValidator             = qaValidator;
        this.buildVerificationValidator = buildVerificationValidator;
        this.secOpsValidator         = secOpsValidator;
        this.controllerValidator     = controllerValidator;
    }

    @PostConstruct
    void init() {
        registry.put(MidasState.SYSTEM_ANALYSIS,      systemAnalystValidator);
        registry.put(MidasState.ARCHITECTURE_DESIGN,  architectValidator);
        registry.put(MidasState.INTEGRATION_STRATEGY, integrationValidator);
        registry.put(MidasState.CODE_GENERATION,      implementationValidator);
        registry.put(MidasState.TEST_GENERATION,      qaValidator);
        registry.put(MidasState.BUILD_VERIFICATION,   buildVerificationValidator);
        registry.put(MidasState.SECOPS_AUDIT,         secOpsValidator);
        registry.put(MidasState.PRODUCT_REVIEW,       controllerValidator);
        log.debug("ValidatorRegistry initialized with {} entries.", registry.size());
    }

    /**
     * Returns the validator for the given state, or {@link Optional#empty()} for
     * non-processing states.
     */
    public Optional<GoalKeeperValidator> getValidator(MidasState state) {
        if (state == null) return Optional.empty();
        return Optional.ofNullable(registry.get(state));
    }

    /** Returns {@code true} if {@code state} has a registered validator. */
    public boolean hasValidator(MidasState state) {
        return state != null && registry.containsKey(state);
    }
}
