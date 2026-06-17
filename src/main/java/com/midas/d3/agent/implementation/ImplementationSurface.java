package com.midas.d3.agent.implementation;

/**
 * Bounded internal pass within {@link com.midas.d3.statemachine.MidasState#CODE_GENERATION}
 * when {@code runtime_environment.execution_model} is {@code HYBRID}.
 */
public enum ImplementationSurface {
    CLIENT,
    SERVER
}
