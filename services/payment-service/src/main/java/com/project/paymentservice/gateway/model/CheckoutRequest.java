package com.project.paymentservice.gateway.model;

import com.project.paymentservice.enums.PaymentMethodType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Provider-neutral checkout creation request.
 *
 * Contains everything a gateway implementation needs to open
 * a provider payment/checkout session.
 *
 * Design notes:
 *
 *   - amount/currency come from the authoritative Booking snapshot,
 *     NOT from the client request.
 *
 *   - providerIdempotencyKey is a stable UUID generated once when the
 *     PaymentAttempt is created (INITIALIZING). Reusing it on retry
 *     lets the provider deduplicate concurrent/retry calls safely.
 *
 *   - bookingExpiresAt is the Booking-side effective payment window
 *     deadline. The gateway MUST ensure the provider checkout expires
 *     strictly before this timestamp.
 */
public record CheckoutRequest(

        UUID paymentId,

        UUID attemptId,

        UUID bookingId,

        BigDecimal amount,

        String currency,

        PaymentMethodType paymentMethod,

        String providerIdempotencyKey,

        /**
         * Booking effective payment-window deadline (UTC).
         *
         * Upper bound for provider checkout expiry.
         * Provider checkout MUST expire before this timestamp.
         */
        java.time.LocalDateTime bookingExpiresAt

) {
}