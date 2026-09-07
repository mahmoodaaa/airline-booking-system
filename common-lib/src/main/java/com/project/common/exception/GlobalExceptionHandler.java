package com.project.common.exception;


import com.project.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;

import java.util.List;
import java.util.stream.Collectors;

@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<ValidationError>> handleValidationErrors(
            MethodArgumentNotValidException ex, WebRequest request) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.toList());

        ValidationError validationError = ValidationError.builder()
                .uri(request.getDescription(false).replace("uri=", ""))
                .errors(errors)
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failure(HttpStatus.BAD_REQUEST, "Validation failed", validationError));
    }

    private String formatFieldError(FieldError fieldError) {
        return String.format("%s: %s", fieldError.getField(), fieldError.getDefaultMessage());
    }


    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<ErrorDetails>> handleHttpMessageNotReadable(
            org.springframework.http.converter.HttpMessageNotReadableException ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Malformed JSON request", ex, request);
    }

    @ExceptionHandler(ApiBaseException.class)
    public ResponseEntity<ApiResponse<ErrorDetails>> handleApiBaseException(
            ApiBaseException ex, WebRequest request) {
        return buildErrorResponse(ex.getStatusCode(), ex.getMessage(), ex, request);
    }

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<ErrorDetails>> handleOptimisticLockingFailureException(
            org.springframework.dao.OptimisticLockingFailureException ex, WebRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, "Resource was updated by another transaction. Please try again.", ex, request);
    }

    @ExceptionHandler(org.springframework.data.core.PropertyReferenceException.class)
    public ResponseEntity<ApiResponse<ErrorDetails>> handleInvalidSortProperty(
            org.springframework.data.core.PropertyReferenceException ex, WebRequest request) {

        ErrorDetails error = ErrorDetails.builder()
                .message("Invalid sort field: " + ex.getPropertyName())
                .path(request.getDescription(false).replace("uri=", ""))
                .exceptionType(ex.getClass().getSimpleName())
                .status(HttpStatus.BAD_REQUEST)
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failure(HttpStatus.BAD_REQUEST, "Invalid sort field", error));
    }

    @ExceptionHandler(org.springframework.dao.InvalidDataAccessApiUsageException.class)
    public ResponseEntity<ApiResponse<ErrorDetails>> handleInvalidDataAccessApiUsage(
            org.springframework.dao.InvalidDataAccessApiUsageException ex, WebRequest request) {

        ErrorDetails error = ErrorDetails.builder()
                .message(ex.getMessage())
                .path(request.getDescription(false).replace("uri=", ""))
                .exceptionType(ex.getClass().getSimpleName())
                .status(HttpStatus.BAD_REQUEST)
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failure(HttpStatus.BAD_REQUEST, "Invalid data access or sort expression", error));
    }


    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<ErrorDetails>> handleGlobalException(
            Exception ex, WebRequest request) {
        log.error("Unexpected error occurred: ", ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later.", ex, request);
    }

    private ResponseEntity<ApiResponse<ErrorDetails>> buildErrorResponse(
            HttpStatus status, String message, Exception ex, WebRequest request) {
        ErrorDetails error = ErrorDetails.builder()
                .message(ex.getMessage() != null ? ex.getMessage() : message)
                .path(request.getDescription(false).replace("uri=", ""))
                .exceptionType(ex.getClass().getSimpleName())
                .status(status)
                .build();

        log.warn("{}: {}", status, error.getMessage());
        return ResponseEntity.status(status).body(ApiResponse.failure(status, message, error));
    }
}