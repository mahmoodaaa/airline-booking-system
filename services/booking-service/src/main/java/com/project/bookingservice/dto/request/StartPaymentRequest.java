package com.project.bookingservice.dto.request;

import java.util.UUID;

/**
 * Body for POST /internal/bookings/{bookingId}/start-payment.
 *
 * The endpoint is called with a SERVICE JWT, but Booking Service still
 * validates that the booking belongs to the original customer (userId).
 */
import jakarta.validation.constraints.NotNull;

public record StartPaymentRequest(
        @NotNull UUID userId) {
}