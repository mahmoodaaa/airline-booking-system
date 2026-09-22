package com.project.paymentservice.client.feign;

import com.project.common.response.ApiResponse;
import com.project.paymentservice.client.dto.BookingPaymentContext;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.UUID;

/**
 * Feign client that calls booking-service internal endpoints.
 *
 * URL is resolved from ${booking.service.url} — no Eureka discovery.
 *
 * - GET  /internal/bookings/{bookingId}/payment-context
 *       → fetch booking financial context before initiating a charge
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

    @GetMapping("/internal/bookings/{bookingId}/payment-context")
    ApiResponse<BookingPaymentContext> getPaymentContext(
            @PathVariable("bookingId") UUID bookingId
    );

    @PostMapping("/internal/bookings/{bookingId}/payments/{paymentId}/confirm")
    ApiResponse<Void> confirmBooking(
            @PathVariable("bookingId") UUID bookingId,
            @PathVariable("paymentId") UUID paymentId
    );
}
