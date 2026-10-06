package com.project.paymentservice.client.exception;

import com.project.common.exception.ApiBaseException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when booking-service explicitly and definitively rejects the integration request.
 * E.g. Booking NOT_FOUND (404) or Booking CONFLICT (409) because it's cancelled/expired.
 */
public class BookingIntegrationDefinitiveException extends ApiBaseException {
    public BookingIntegrationDefinitiveException(String message) {
        super(message, HttpStatus.CONFLICT);
    }
}

