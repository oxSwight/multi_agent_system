package com.midas.d3.api.dto;

/**
 * Response body for {@code POST /api/v1/pipelines}.
 */
public record StartPipelineResponse(String runId, String state) {}
