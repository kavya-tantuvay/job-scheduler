package com.jobscheduler.common.exception;

import com.jobscheduler.common.dto.ApiError;
import com.jobscheduler.common.dto.ApiError.FieldViolation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Request validation failed", request, violations);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Malformed or unreadable request body", request, List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String message = "Invalid value '%s' for parameter '%s'".formatted(ex.getValue(), ex.getName());
        return build(HttpStatus.BAD_REQUEST, message, request, List.of());
    }

    /** Unknown sort property in a pageable request, e.g. {@code ?sort=nope}. */
    @ExceptionHandler(PropertyReferenceException.class)
    ResponseEntity<ApiError> handleBadSort(PropertyReferenceException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ApiError> handleInvalid(InvalidRequestException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiError> handleConflict(ConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleOther(Exception ex, HttpServletRequest request) {
        // Spring MVC's own exceptions (405, 415, 404 for unknown paths, ...) carry their status.
        if (ex instanceof ErrorResponse errorResponse) {
            String detail = errorResponse.getBody().getDetail();
            return build(errorResponse.getStatusCode(), detail != null ? detail : ex.getMessage(), request, List.of());
        }
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", request, List.of());
    }

    private ResponseEntity<ApiError> build(HttpStatusCode status, String message, HttpServletRequest request,
                                           List<FieldViolation> violations) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String reason = resolved != null ? resolved.getReasonPhrase() : String.valueOf(status.value());
        ApiError body = new ApiError(Instant.now(), status.value(), reason, message,
                request.getRequestURI(), violations);
        return ResponseEntity.status(status).body(body);
    }
}
