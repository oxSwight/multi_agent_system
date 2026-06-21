package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;

public final class ImplementationOutputUnwrapper {

    private ImplementationOutputUnwrapper() {}

    public record UnwrappedEnvelope(JsonNode sourceFiles, JsonNode featureManifest) {}

    public static UnwrappedEnvelope unwrap(JsonNode envelope) {
        if (envelope == null || envelope.isNull() || envelope.isMissingNode()) {
            throw new IllegalArgumentException("Implementation envelope must not be null or missing.");
        }
        if (!envelope.isObject()) {
            throw new IllegalArgumentException("Implementation envelope root must be a JSON object.");
        }

        JsonNode sourceFiles = envelope.get("source_files");
        if (sourceFiles == null || sourceFiles.isNull() || sourceFiles.isMissingNode()) {
            throw new IllegalArgumentException("Implementation envelope missing required object field: 'source_files'.");
        }
        if (!sourceFiles.isObject()) {
            throw new IllegalArgumentException("Implementation envelope field 'source_files' must be a JSON object.");
        }

        JsonNode featureManifest = envelope.get("feature_manifest");
        if (featureManifest == null || featureManifest.isNull() || featureManifest.isMissingNode()) {
            throw new IllegalArgumentException("Implementation envelope missing required array field: 'feature_manifest'.");
        }
        if (!featureManifest.isArray()) {
            throw new IllegalArgumentException("Implementation envelope field 'feature_manifest' must be an array.");
        }

        return new UnwrappedEnvelope(sourceFiles, featureManifest);
    }
}
