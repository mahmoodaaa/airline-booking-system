package com.project.bookingservice.exception;

/**
 * Thrown when a booking operation encounters an internal consistency failure.
 * Examples:
 *  - finalizeBooking() failed after reserve succeeded and compensation also failed
 *  - CANCELLING→CANCELLED transition failed after releaseSeats succeeded
 *
 * These are CRITICAL scenarios that require manual reconciliation.
 */
public class BookingProcessingException extends RuntimeException {
    public BookingProcessingException(String message) {
        super(message);
    }

    public BookingProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
