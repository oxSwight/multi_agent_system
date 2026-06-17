package com.midas.d3.api;

import com.midas.d3.api.dto.ErrorResponse;
import com.midas.d3.statemachine.PipelineOrchestrator;
import com.midas.d3.validation.ValidationHookException;
import com.midas.d3.web.RunNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Centralised exception-to-HTTP-response mapping for the MIDAS REST API.
 *
 * <table>
 *   <caption>Exception mapping</caption>
 *   <tr><th>Exception</th><th>HTTP status</th></tr>
 *   <tr><td>{@link PipelineOrchestrator.PipelineNotFoundException}</td><td>404 Not Found</td></tr>
 *   <tr><td>{@link IllegalArgumentException}</td><td>400 Bad Request</td></tr>
 *   <tr><td>{@link MethodArgumentNotValidException}</td><td>400 Bad Request (with violations list)</td></tr>
 *   <tr><td>{@link ValidationHookException}</td><td>422 Unprocessable Entity (with violations list)</td></tr>
 *   <tr><td>{@link Exception} (catch-all)</td><td>500 Internal Server Error</td></tr>
 * </table>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── 404 Not Found ────────────────────────────────────────────────────────

    @ExceptionHandler(PipelineOrchestrator.PipelineNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(PipelineOrchestrator.PipelineNotFoundException ex,
                                        HttpServletRequest req) {
        log.warn("[API] PipelineNotFound at {}: {}", req.getRequestURI(), ex.getMessage());
        return ErrorResponse.of(
                HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(RunNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleRunNotFound(RunNotFoundException ex, HttpServletRequest req) {
        log.warn("[API] RunNotFound at {}: {}", req.getRequestURI(), ex.getMessage());
        return ErrorResponse.of(
                HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(ArtifactNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleArtifactNotFound(ArtifactNotFoundException ex, HttpServletRequest req) {
        log.warn("[API] ArtifactNotFound at {}: {}", req.getRequestURI(), ex.getMessage());
        return ErrorResponse.of(
                HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage(), req.getRequestURI());
    }

    // ── 400 Bad Request ──────────────────────────────────────────────────────

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleIllegalArgument(IllegalArgumentException ex,
                                               HttpServletRequest req) {
        log.warn("[API] IllegalArgument at {}: {}", req.getRequestURI(), ex.getMessage());
        return ErrorResponse.of(
                HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage(), req.getRequestURI());
    }

    /**
     * Handles {@code @Valid}-triggered bean-validation failures on {@code @RequestBody}.
     * Collects all field-level constraint messages into the {@code violations} list.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleBeanValidation(MethodArgumentNotValidException ex,
                                              HttpServletRequest req) {
        List<String> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.toList());
        log.warn("[API] Bean validation failed at {}: {}", req.getRequestURI(), violations);
        return ErrorResponse.withViolations(
                HttpStatus.BAD_REQUEST.value(), "Bad Request",
                "Request validation failed", req.getRequestURI(), violations);
    }

    // ── 422 Unprocessable Entity ─────────────────────────────────────────────

    /**
     * Handles GoalKeeper schema violations bubbled up from the state machine.
     * Returns the full list of structural violations for diagnostic purposes.
     */
    @ExceptionHandler(ValidationHookException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse handleValidationHook(ValidationHookException ex,
                                              HttpServletRequest req) {
        log.warn("[API] ValidationHookException at {}: {}", req.getRequestURI(), ex.getMessage());
        return ErrorResponse.withViolations(
                HttpStatus.UNPROCESSABLE_ENTITY.value(), "Unprocessable Entity",
                ex.getMessage(), req.getRequestURI(), ex.getViolations());
    }

    // ── 415 Unsupported Media Type / 405 Method Not Allowed ──────────────────

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public ErrorResponse handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex,
                                                    HttpServletRequest req) {
        log.warn("[API] Unsupported media type at {}: {}", req.getRequestURI(), ex.getMessage());
        return ErrorResponse.of(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(), "Unsupported Media Type",
                ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ErrorResponse handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                HttpServletRequest req) {
        log.warn("[API] Method not allowed at {}: {}", req.getRequestURI(), ex.getMessage());
        return ErrorResponse.of(
                HttpStatus.METHOD_NOT_ALLOWED.value(), "Method Not Allowed",
                ex.getMessage(), req.getRequestURI());
    }

    // ── 500 Internal Server Error ────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("[API] Unexpected error at {}: {}", req.getRequestURI(), ex.getMessage(), ex);
        return ErrorResponse.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error",
                "An unexpected error occurred.", req.getRequestURI());
    }
}
