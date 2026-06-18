package com.midas.d3.agent.implementation;

public class PatchValidationException extends RuntimeException {

    public PatchValidationException(String message) {
        super(message);
    }

    public PatchValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
