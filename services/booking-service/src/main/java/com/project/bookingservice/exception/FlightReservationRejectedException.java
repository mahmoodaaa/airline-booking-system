package com.project.bookingservice.exception;

/**
 * Thrown when the flight service definitively rejects a reservation request
 * (e.g. 400 Bad Request, 404 Not Found, 409 Conflict).
 * This indicates that the reservation did NOT happen.
 */
import org.springframework.http.HttpStatusCode;

public class FlightReservationRejectedException extends RuntimeException {
    
    private final HttpStatusCode status;

    public FlightReservationRejectedException(String message, HttpStatusCode status) {
        super(message);
        this.status = status;
    }

    public HttpStatusCode getStatus() {
        return status;
    }
}
