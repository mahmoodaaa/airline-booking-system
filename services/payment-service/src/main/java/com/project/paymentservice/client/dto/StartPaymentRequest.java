package com.project.paymentservice.client.dto;

import java.util.UUID;

/**
 * Body for POST /internal/bookings/{bookingId}/start-payment.
 *
 * Even though the call is authorized via SERVICE JWT, we pass
 * the userId so Booking Service can enforce that the booking
 * belongs to the correct customer before starting the protected payment window.
 */
public record StartPaymentRequest(UUID userId) {
}
