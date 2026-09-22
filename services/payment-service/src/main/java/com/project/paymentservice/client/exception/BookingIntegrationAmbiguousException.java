package com.project.paymentservice.client.exception;

/**
 * Thrown when communication with booking-service results in an ambiguous outcome.
 * E.g. connection timeout, 5xx server errors, etc.
 * The operation might have succeeded or failed; manual or automated reconciliation is required.
 */
public class BookingIntegrationAmbiguousException extends RuntimeException {
    public BookingIntegrationAmbiguousException(String message, Throwable cause) {
        super(message, cause);
    }

    public BookingIntegrationAmbiguousException(String message) {
        super(message);
    }
}
