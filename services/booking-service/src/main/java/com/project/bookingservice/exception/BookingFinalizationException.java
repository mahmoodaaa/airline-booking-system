package com.project.bookingservice.exception;

/**
 * Thrown when a booking cannot be finalized due to technical issues
 * (e.g. database failure, network error).
 * This usually maps to a 500 or 503 response.
 */
public class BookingFinalizationException extends RuntimeException {
    public BookingFinalizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
