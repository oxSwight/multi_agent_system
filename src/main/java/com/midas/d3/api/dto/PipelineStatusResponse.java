package com.midas.d3.api.dto;

/**
 * Lightweight status response returned by status and submit endpoints.
 */
public record PipelineStatusResponse(String runId, String state) {}
