package com.project.bookingservice.exception;

import com.project.common.exception.ErrorDetails;
import com.project.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * Booking-domain-specific exception handler.
 *
 * Handles exceptions thrown only within the Booking lifecycle.
 * Generic errors (validation, DB, etc.) fall through to GlobalExceptionHandler in common-lib.
 *
 * Priority: Spring picks the most specific handler first.
 * This class handles booking exceptions before GlobalExceptionHandler.
 */
@Slf4j
@RestControllerAdvice
public class BookingExceptionHandler {

    // ─────────────────────────────────────────────
    // Idempotency conflicts → 409
    // ─────────────────────────────────────────────

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiResponse<ErrorDetails>> handleIdempotencyConflict(
            IdempotencyConflictException ex, WebRequest request) {

        log.warn("Idempotency conflict: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, ex, request);
    }

    // ─────────────────────────────────────────────
    // Booking state violations → 409
    // ─────────────────────────────────────────────

    @ExceptionHandler(BookingStateException.class)
    public ResponseEntity<ApiResponse<ErrorDetails>> handleBookingState(
            BookingStateException ex, WebRequest request) {

        log.warn("Booking state error: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, ex, request);
    }

    // ─────────────────────────────────────────────
    // Ongoing processing conflict → 409
    // ─────────────────────────────────────────────

    @ExceptionHandler(BookingProcessingException.class)
    public ResponseEntity<ApiResponse<ErrorDetails>> handleBookingProcessing(
            BookingProcessingException ex, WebRequest request) {

        log.warn("Booking processing conflict: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, ex, request);
    }

    // ─────────────────────────────────────────────
    // Internal consistency/finalization failure → 500
    // These are CRITICAL — logged at ERROR level
    // ─────────────────────────────────────────────

    @ExceptionHandler(BookingFinalizationException.class)
    public ResponseEntity<ApiResponse<ErrorDetails>> handleBookingFinalization(
            BookingFinalizationException ex, WebRequest request) {

        log.error("CRITICAL booking finalization error: {}", ex.getMessage());
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, ex, request);
    }

    // ─────────────────────────────────────────────
    // Concurrent duplicate claim (UNIQUE constraint race) → 409
    // Handled here rather than letting it bubble as 500
    // ─────────────────────────────────────────────

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<ErrorDetails>> handleDataIntegrity(
            DataIntegrityViolationException ex, WebRequest request) {

        log.warn("DataIntegrityViolation — likely duplicate idempotency key: {}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT,
                "A booking with this idempotency key is already being processed", ex, request);
    }


    @ExceptionHandler(BookingReconciliationException.class)
    public ResponseEntity<ApiResponse<?>> handleReconciliation(
            BookingReconciliationException ex) {
        log.error("CRITICAL booking reconciliation error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.failure(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), null));
    }


    @ExceptionHandler({
            org.springframework.security.access.AccessDeniedException.class,
            org.springframework.security.authorization.AuthorizationDeniedException.class
    })
    public ResponseEntity<ApiResponse<ErrorDetails>> handleAccessDeniedException(
            Exception ex, WebRequest request) {
        log.warn("Access denied: {}", ex.getMessage());
        return buildResponse(HttpStatus.FORBIDDEN, "Access Denied", ex, request);
    }

    @ExceptionHandler(FlightReservationRejectedException.class)
    public ResponseEntity<ApiResponse<ErrorDetails>> handleFlightReservationRejected(
            FlightReservationRejectedException ex, WebRequest request) {
        org.springframework.http.HttpStatusCode status = ex.getStatus();
        log.warn("Flight reservation rejected: {} with status {}", ex.getMessage(), status);
        return buildResponse(org.springframework.http.HttpStatus.valueOf(status.value()), ex, request);
    }


    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private ResponseEntity<ApiResponse<ErrorDetails>> buildResponse(
            HttpStatus status, Exception ex, WebRequest request) {

        return buildResponse(status, ex.getMessage(), ex, request);
    }

    private ResponseEntity<ApiResponse<ErrorDetails>> buildResponse(
            HttpStatus status, String message, Exception ex, WebRequest request) {

        ErrorDetails details = ErrorDetails.builder()
                .message(message)
                .path(request.getDescription(false).replace("uri=", ""))
                .exceptionType(ex.getClass().getSimpleName())
                .status(status)
                .build();

        return ResponseEntity.status(status)
                .body(ApiResponse.failure(status, message, details));
    }
}
