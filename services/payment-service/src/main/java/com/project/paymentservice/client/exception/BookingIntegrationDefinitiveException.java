package com.project.paymentservice.client.exception;

/**
 * Thrown when booking-service explicitly and definitively rejects the integration request.
 * E.g. Booking NOT_FOUND (404) or Booking CONFLICT (409) because it's cancelled/expired.
 */
public class BookingIntegrationDefinitiveException extends RuntimeException {
    public BookingIntegrationDefinitiveException(String message) {
        super(message);
    }
}
