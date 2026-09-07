package com.project.bookingservice.exception;

/**
 * Thrown when a booking state transition is not legal from the current state.
 * Examples:
 *  - Cancel requested on EXPIRED booking → 409
 *  - Cancel requested on CANCELLING booking (another cancel in progress) → 409
 *  - Final transition failed after releaseSeats succeeded → treated as CRITICAL
 */
public class BookingStateException extends RuntimeException {
    public BookingStateException(String message) {
        super(message);
    }
}
