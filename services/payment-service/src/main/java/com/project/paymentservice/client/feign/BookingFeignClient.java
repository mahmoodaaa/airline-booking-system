package com.project.paymentservice.client.feign;

import com.project.common.response.ApiResponse;
import com.project.paymentservice.client.dto.BookingPaymentContext;
import com.project.paymentservice.client.dto.StartPaymentRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.UUID;

/**
 * Feign client that calls booking-service internal endpoints.
 *
 * URL is resolved from ${booking.service.url} — no Eureka discovery.
 *
 * - POST /internal/bookings/{bookingId}/start-payment
 *       → Atomically starts the Booking protected payment window
 *       * and returns the effective expiresAt and returns context
 *
 * - POST /internal/bookings/{bookingId}/payments/{paymentId}/confirm
 *       → confirm booking after Stripe payment succeeds
 *         (idempotent: safe to call multiple times with same IDs)
 */
@FeignClient(
        name          = "booking-service",
        url           = "${booking.service.url}",
        configuration = BookingFeignConfig.class
)
public interface BookingFeignClient {

    @PostMapping("/internal/bookings/{bookingId}/start-payment")
    ApiResponse<BookingPaymentContext> startPayment(
            @PathVariable("bookingId") UUID bookingId,
            @RequestBody StartPaymentRequest request);

    @PostMapping("/internal/bookings/{bookingId}/payments/{paymentId}/confirm")
    ApiResponse<Void> confirmBooking(@PathVariable("bookingId") UUID bookingId, @PathVariable("paymentId") UUID paymentId);
}
